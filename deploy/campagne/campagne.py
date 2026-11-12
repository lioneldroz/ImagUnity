#!/usr/bin/env python3
"""Campagne de tests de la preuve de concept ImagUnity (phase 8).

Pilote la pile Docker Compose (relais IU-T, annuaire, deux box, deux Orthanc) et déroule les scénarios
de la note d'architecture. Chaque scénario envoie une étude synthétique d'Orthanc A vers Orthanc B à
travers le relais, mesure les durées et vérifie l'arrivée instance par instance.

    ./campagne/prepare.sh
    docker compose -f docker-compose.yml -f campagne/compose.campagne.yml up -d --build
    campagne/.venv/bin/python campagne/campagne.py tout            # ou une liste de scénarios

Les box doivent être enrôlées dans l'annuaire (deploy/README.md) ; le pilote les appaire par l'annuaire au besoin.
Surcouches Compose supplémentaires de la pile (TPM logiciel, mises à jour signées…) : CAMPAGNE_COMPOSE, chemins
relatifs à deploy/ séparés par des espaces.

Données exclusivement synthétiques : patient « SYNTHETIQUE^CAMPAGNE », pixels aléatoires.
"""

import argparse
import base64
import hashlib
import io
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import CTImageStorage, ExplicitVRLittleEndian, generate_uid

HERE = Path(__file__).resolve().parent
DEPLOY = HERE.parent
PROJECT = "imagunity"
ORTHANC_A = "http://localhost:8042"
ORTHANC_B = "http://localhost:8043"
BOX_A = "http://localhost:8081"
BOX_B = "http://localhost:8082"
NETSHOOT = "nicolaka/netshoot:v0.14"
PATIENT_NAME = "SYNTHETIQUE^CAMPAGNE"
MO = 1024 * 1024


# --- Outils ------------------------------------------------------------------

def log(message):
    print(f"[{datetime.now():%H:%M:%S}] {message}", flush=True)


# Compte opérateur des box de l'environnement de test (docker-compose.yml).
CONSOLE_USER = os.environ.get("IMAGUNITY_CONSOLE_USER", "operateur")
CONSOLE_PASSWORD = os.environ.get("IMAGUNITY_CONSOLE_PASSWORD", "essai-synthetique")


def http(method, url, body=None, timeout=600, raw=False):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if url.startswith((BOX_A, BOX_B)):
        token = base64.b64encode(f"{CONSOLE_USER}:{CONSOLE_PASSWORD}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
        request.add_header("X-ImagUnity-Console", "1")
    if data is not None and not isinstance(body, bytes):
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        content = response.read()
        return content if raw else (json.loads(content) if content else None)


def run(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True).stdout.strip()


def compose(*args):
    files = ["-f", str(DEPLOY / "docker-compose.yml"), "-f", str(HERE / "compose.campagne.yml")]
    for extra in os.environ.get("CAMPAGNE_COMPOSE", "").split():
        files += ["-f", str(DEPLOY / extra)]
    return run("docker", "compose", "-p", PROJECT, *files, *args)


def container(service):
    return f"{PROJECT}-{service}-1"


def netshoot(target, script, privileged=True):
    """Exécute un script dans l'espace réseau d'un conteneur (tc, iptables, ip)."""
    args = ["docker", "run", "--rm", "--net", f"container:{container(target)}"]
    if privileged:
        args += ["--cap-add", "NET_ADMIN"]
    return run(*args, NETSHOOT, "sh", "-c", script)


def relais_ip(service):
    return run("docker", "inspect", "-f",
               '{{(index .NetworkSettings.Networks "' + PROJECT + '_relais").IPAddress}}', container(service))


def relais_iface(service):
    """Interface du conteneur sur le réseau des relais (côté « Internet »)."""
    ip = relais_ip(service)
    out = netshoot(service, "ip -o -4 addr show", privileged=False)
    for line in out.splitlines():
        if f" {ip}/" in line:
            return line.split()[1]
    raise RuntimeError(f"Interface relais introuvable pour {service}")


def wait(condition, timeout, step=0.5, what="condition"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(step)
    raise TimeoutError(f"Délai dépassé : {what}")


# --- Pile et appairage ---------------------------------------------------------

def _bound(base, peer_box):
    return any(p.get("boxId") == peer_box for p in http("GET", f"{base}/api/v1/peers"))


def ensure_paired():
    """Appaire A et B par l'annuaire (console de A, box B de l'annuaire), si ce n'est pas déjà fait."""
    wait(lambda: _up(BOX_A) and _up(BOX_B), 180, 2, "démarrage des box")
    box_a = http("GET", f"{BOX_A}/api/v1/directory")["boxId"]
    box_b = http("GET", f"{BOX_B}/api/v1/directory")["boxId"]
    if _bound(BOX_A, box_b) and _bound(BOX_B, box_a):
        return
    wait(lambda: any(b["boxId"] == box_b and b["reachable"] for b in http("GET", f"{BOX_A}/api/v1/directory")["boxes"]),
         180, 2, "box B active dans l'annuaire vu par A, avec son adresse IU-T")
    log("Appairage des box A et B par l'annuaire")
    http("POST", f"{BOX_A}/api/v1/directory/boxes/{box_b}/pair", {"name": "box-b"})
    wait(lambda: _bound(BOX_A, box_b) and _bound(BOX_B, box_a), 120, 1, "appairage et poignée de main")


def _up(base):
    try:
        http("GET", f"{base}/api/v1/box", timeout=3)
        return True
    except (urllib.error.URLError, OSError):
        return False


# --- Études synthétiques -------------------------------------------------------

def make_study(directory, series, per_series, side, label, descriptions=None):
    """Étude CT synthétique : pixels aléatoires (incompressibles), identité de patient fictive.

    per_series : nombre d'instances par série, ou liste (une valeur par série) ; descriptions : SeriesDescription
    de chaque série (facultatif).
    """
    study_uid = generate_uid()
    files = []
    counts = per_series if isinstance(per_series, list) else [per_series] * series
    for s in range(1, series + 1):
        series_uid = generate_uid()
        for i in range(1, counts[s - 1] + 1):
            meta = FileMetaDataset()
            meta.MediaStorageSOPClassUID = CTImageStorage
            meta.MediaStorageSOPInstanceUID = generate_uid()
            meta.TransferSyntaxUID = ExplicitVRLittleEndian
            ds = Dataset()
            ds.file_meta = meta
            ds.SOPClassUID = CTImageStorage
            ds.SOPInstanceUID = meta.MediaStorageSOPInstanceUID
            ds.StudyInstanceUID = study_uid
            ds.SeriesInstanceUID = series_uid
            ds.PatientName = PATIENT_NAME
            ds.PatientID = f"IU-CAMPAGNE-{label}"
            ds.StudyDescription = f"Campagne ImagUnity {label}"
            ds.Modality = "CT"
            ds.SeriesNumber = s
            if descriptions:
                ds.SeriesDescription = descriptions[s - 1]
            ds.InstanceNumber = i
            ds.Rows = ds.Columns = side
            ds.SamplesPerPixel = 1
            ds.PhotometricInterpretation = "MONOCHROME2"
            ds.BitsAllocated = ds.BitsStored = 16
            ds.HighBit = 15
            ds.PixelRepresentation = 0
            ds.PixelData = os.urandom(side * side * 2)
            path = directory / f"{s:03d}-{i:05d}.dcm"
            ds.save_as(path, enforce_file_format=True)
            files.append(path)
    return study_uid, files


def upload_to_orthanc_a(files):
    ids = set()
    for path in files:
        ids.add(http("POST", f"{ORTHANC_A}/instances", path.read_bytes())["ParentStudy"])
    assert len(ids) == 1, ids
    return ids.pop()


def orthanc_study(base, study_uid):
    found = http("POST", f"{base}/tools/lookup", study_uid.encode())
    return next((f["ID"] for f in found if f["Type"] == "Study"), None)


def delete_study(base, study_uid):
    study = orthanc_study(base, study_uid)
    if study:
        http("DELETE", f"{base}/studies/{study}")


# --- Mesure d'un transfert -----------------------------------------------------

class Transfer:
    """Envoie une étude d'Orthanc A vers la box B et suit le transfert jusqu'à son terme."""

    def __init__(self, label, series, per_series, side=512, descriptions=None):
        self.label = label
        self.series, self.per_series, self.side = series, per_series, side
        self.descriptions = descriptions
        self.count = sum(per_series) if isinstance(per_series, list) else series * per_series
        self.series_uids = []
        self.study_uid = None
        self.size = 0
        self.timeline = {}
        self.t0 = None
        self.result = {}

    def prepare(self):
        tmp = Path(tempfile.mkdtemp(prefix="iu-campagne-"))
        try:
            log(f"[{self.label}] génération de {self.count} instances {self.side}×{self.side}")
            self.study_uid, files = make_study(tmp, self.series, self.per_series, self.side, self.label,
                                               self.descriptions)
            import pydicom
            self.series_uids = list(dict.fromkeys(pydicom.dcmread(f, stop_before_pixels=True).SeriesInstanceUID
                                                  for f in files))
            self.size = sum(f.stat().st_size for f in files)
            log(f"[{self.label}] import dans Orthanc A ({self.size / MO:.1f} Mo)")
            self.orthanc_a_id = upload_to_orthanc_a(files)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def send(self):
        log(f"[{self.label}] C-STORE Orthanc A → box A")
        self.t0 = time.monotonic()
        job = http("POST", f"{ORTHANC_A}/modalities/imagunity-b/store",
                   {"Resources": [self.orthanc_a_id], "Synchronous": False})["ID"]
        self.job = job

    def elapsed(self):
        return round(time.monotonic() - self.t0, 2)

    def poll(self):
        """Relève les états vus depuis le début ; renvoie l'état courant côté A."""
        if "cstore" not in self.timeline:
            state = http("GET", f"{ORTHANC_A}/jobs/{self.job}")["State"]
            if state == "Success":
                self.timeline["cstore"] = self.elapsed()
            elif state == "Failure":
                raise RuntimeError("C-STORE en échec")
        transfer = self.box_transfer()
        if transfer:
            self.timeline.setdefault(transfer["state"], self.elapsed())
            if transfer["state"] == "FAILED":
                self.result["errorMessage"] = transfer["errorMessage"]
        if "restored" not in self.timeline:
            study = orthanc_study(ORTHANC_B, self.study_uid)
            if study and int(http("GET", f"{ORTHANC_B}/studies/{study}/statistics")["CountInstances"]) == self.count:
                self.timeline["restored"] = self.elapsed()
        return transfer["state"] if transfer else None

    def box_transfer(self):
        transfers = http("GET", f"{BOX_A}/api/v1/transfers?limit=50")
        return next((t for t in transfers if t["studyInstanceUid"] == self.study_uid), None)

    def wait_for(self, predicate, timeout, what):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.poll()
            if predicate(state):
                return state
            time.sleep(0.5)
        raise TimeoutError(f"[{self.label}] délai dépassé : {what}")

    def wait_done(self, timeout=1800):
        state = self.wait_for(lambda s: s in ("ACKNOWLEDGED", "FAILED") and "restored" in self.timeline
                              or s == "FAILED", timeout, "fin du transfert")
        # L'accusé arrive juste après la restitution : on laisse le temps de le voir.
        if state != "FAILED":
            self.wait_for(lambda s: s == "ACKNOWLEDGED", 120, "accusé")
        return self.poll()

    def verify(self):
        """Compare chaque instance d'Orthanc B à celle d'Orthanc A, jeu de données octet par octet."""
        a = instances_by_sop(ORTHANC_A, self.study_uid)
        b = instances_by_sop(ORTHANC_B, self.study_uid)
        identical = sum(1 for sop in a if sop in b and dataset_digest(ORTHANC_A, a[sop])
                        == dataset_digest(ORTHANC_B, b[sop]))
        return {"instancesA": len(a), "instancesB": len(b), "identiques": identical}

    def report(self, state):
        t = self.timeline
        r = {"scenario": self.label, "instances": self.count, "tailleMo": round(self.size / MO, 1),
             "etatFinal": state, "chronologie_s": t}
        if "restored" in t and "cstore" in t:
            r["debitBoutEnBout_Mo_s"] = round(self.size / MO / max(t["restored"] - t["cstore"], 0.01), 2)
        if "ANNOUNCED" in t and "PACKAGED" in t:
            r["depotRelais_s"] = round(t["ANNOUNCED"] - t["PACKAGED"], 2)
        r.update(self.result)
        return r

    def cleanup(self):
        delete_study(ORTHANC_A, self.study_uid)
        delete_study(ORTHANC_B, self.study_uid)


def instances_by_sop(base, study_uid):
    study = orthanc_study(base, study_uid)
    if not study:
        return {}
    return {i["MainDicomTags"]["SOPInstanceUID"]: i["ID"]
            for i in http("GET", f"{base}/studies/{study}/instances")}


def dataset_digest(base, instance):
    """SHA-256 du jeu de données, hors préambule et méta-informations de fichier (groupe 0002)."""
    content = http("GET", f"{base}/instances/{instance}/file", raw=True)
    assert content[128:132] == b"DICM"
    group_length = int.from_bytes(content[140:144], "little")
    return hashlib.sha256(content[144 + group_length:]).hexdigest()


def full_transfer(label, series, per_series, side=512, timeout=1800, during=None, descriptions=None):
    transfer = Transfer(label, series, per_series, side, descriptions)
    transfer.prepare()
    transfer.send()
    if during:
        during(transfer)
    state = transfer.wait_done(timeout)
    result = transfer.report(state)
    if state == "ACKNOWLEDGED":
        result["verification"] = transfer.verify()
    log(f"[{label}] {json.dumps(result, ensure_ascii=False)}")
    transfer.cleanup()
    return result


# --- Scénarios -----------------------------------------------------------------

def scenario_nominal():
    """Envoi d'une étude de 100 instances (≈ 50 Mo) d'Orthanc A vers Orthanc B, sans perturbation."""
    return full_transfer("nominal", 2, 50)


# Ports bien connus, plus ceux de la box (8080 console, 11112 DICOM) et du relais IU-T (8443).
SCAN_PORTS = "1-1024,8080,8443,11112"


def nmap(service):
    ip = relais_ip(service)
    out = run("docker", "run", "--rm", "--network", f"{PROJECT}_relais", NETSHOOT,
              "nmap", "-Pn", "-sT", "-p", SCAN_PORTS, "-T4", "--max-retries", "1", "--host-timeout", "120s",
              "-oG", "-", ip)
    ports = [p for line in out.splitlines() if "Ports:" in line
             for p in line.split("Ports:")[1].split("\t")[0].split(", ")]
    return {"ip": ip, "ports": [p.strip() for p in ports if p.strip()]}


def relais_gateway():
    return run("docker", "network", "inspect", f"{PROJECT}_relais", "-f", "{{(index .IPAM.Config 0).Gateway}}")


def firewall(service, on):
    """Bloque toute connexion entrante côté relais (« Internet »).

    Exception : la passerelle Docker, par laquelle arrivent dans ce labo les ports publiés de la console
    (le pilote de campagne s'en sert) ; dans un établissement, la console n'est joignable que du réseau local.
    """
    iface = relais_iface(service)
    gateway = relais_gateway()
    rules = [f"INPUT -i {iface} -s {gateway} -j ACCEPT", f"INPUT -i {iface} -p tcp --syn -j DROP",
             f"INPUT -i {iface} -p udp -j DROP"]
    if on:
        script = " && ".join(f"iptables -A {r}" for r in rules)
    else:
        script = " ; ".join(f"iptables -D {r}" for r in rules)
    netshoot(service, script)


def scenario_pare_feu():
    """Box derrière un pare-feu bloquant toute connexion entrante côté Internet, avec scan de ports."""
    result = {"scenario": "pare-feu"}
    result["scanAvant"] = {s: nmap(s) for s in ("net-a", "net-b")}
    for s in ("net-a", "net-b"):
        firewall(s, True)
    try:
        result["scanApres"] = {s: nmap(s) for s in ("net-a", "net-b")}
        result["transfert"] = full_transfer("pare-feu", 2, 50)
    finally:
        for s in ("net-a", "net-b"):
            firewall(s, False)
    log(f"[pare-feu] {json.dumps(result, ensure_ascii=False)}")
    return result


# Débit limité à 20 Mbit/s en sortie de chaque box et de chaque relais (tbf). La latence et les pertes
# (netem) ne sont pas disponibles sur tous les noyaux : NETEM=1 les ajoute là où le module existe.
DEGRADATION = ("netem delay 40ms 10ms loss 1% rate 20mbit" if os.environ.get("NETEM") == "1"
               else "tbf rate 20mbit burst 64kb latency 400ms")


def degrade(on):
    for service in ("net-a", "net-b", "relay"):
        iface = relais_iface(service)
        if on:
            netshoot(service, f"tc qdisc add dev {iface} root {DEGRADATION}")
        else:
            netshoot(service, f"tc qdisc del dev {iface} root || true")


def scenario_degrade():
    """Liaison bridée sur tous les trajets box ↔ relais : 20 Mbit/s (+ 40 ± 10 ms et 1 % de pertes avec NETEM=1)."""
    degrade(True)
    try:
        result = full_transfer("liaison-degradee", 2, 100)
    finally:
        degrade(False)
    result["degradation"] = f"{DEGRADATION} (sortie de chaque box et du relais)"
    return result


# Liaison lente : 2 Mbit/s en sortie de chaque box et du relais, pour mesurer la taille de morceau adaptative.
LENTE = os.environ.get("DEGRADATION_LENTE", "tbf rate 2mbit burst 32kb latency 400ms")


def slow_link(on):
    for service in ("net-a", "net-b", "relay"):
        iface = relais_iface(service)
        if on:
            netshoot(service, f"tc qdisc add dev {iface} root {LENTE}")
        else:
            netshoot(service, f"tc qdisc del dev {iface} root || true")


def fresh_link_estimate():
    """Box A sans estimation du débit, comme installée sur un site lent : estimation effacée, box redémarrée."""
    compose("stop", "box-a")
    run("docker", "run", "--rm", "-v", f"{PROJECT}_box-a-data:/d", "ubuntu:24.04", "rm", "-f", "/d/iu/liaison.json")
    compose("start", "box-a")
    wait(lambda: _up(BOX_A), 180, 2, "redémarrage de la box A")
    time.sleep(5)  # relève de la file reprise


def chunk_setting():
    """Taille de morceau des box (IMAGUNITY_IU_CHUNK_SIZE), pour les résultats."""
    env = run("docker", "inspect", "-f", "{{json .Config.Env}}", container("box-a"))
    return next((v.split("=", 1)[1] for v in json.loads(env) if v.startswith("IMAGUNITY_IU_CHUNK_SIZE=")), "auto")


def scenario_liaison_lente():
    """Liaison à 2 Mbit/s : un petit transfert de rodage (mesure du débit), puis une étude de 20 Mo."""
    fresh_link_estimate()
    slow_link(True)
    try:
        full_transfer("liaison-lente-rodage", 1, 4)
        result = full_transfer("liaison-lente", 2, 20)
    finally:
        slow_link(False)
    result["degradation"] = f"{LENTE} (sortie de chaque box et du relais)"
    result["tailleMorceaux"] = chunk_setting()
    t = result["chronologie_s"]
    if "restored" in t and "ANNOUNCED" in t:
        result["restitutionApresTeleversement_s"] = round(t["restored"] - t["ANNOUNCED"], 2)
    return result


def scenario_coupure_lente(cut_seconds=30):
    """Liaison à 2 Mbit/s et coupure de 30 s de la box A au milieu du téléversement d'une étude de 20 Mo."""
    iface = relais_iface("net-a")
    gateway = relais_gateway()
    rules = (f"INPUT -i {iface} ! -s {gateway} -j DROP", f"OUTPUT -o {iface} ! -d {gateway} -j DROP")
    events = {}

    def cut(transfer):
        transfer.wait_for(lambda s: s == "PACKAGED", 600, "empaquetage")
        time.sleep(25)  # téléversement bien entamé
        events["coupure_s"] = transfer.elapsed()
        netshoot("net-a", " && ".join(f"iptables -I {r}" for r in rules))
        try:
            time.sleep(cut_seconds)
        finally:
            netshoot("net-a", " ; ".join(f"iptables -D {r}" for r in rules))
        events["retablissement_s"] = transfer.elapsed()

    fresh_link_estimate()
    slow_link(True)
    try:
        full_transfer("coupure-lente-rodage", 1, 4)
        result = full_transfer("coupure-liaison-lente", 2, 20, during=cut)
    finally:
        slow_link(False)
    result.update(events)
    result["tailleMorceaux"] = chunk_setting()
    return result


def series_count(base, series_uid):
    """Instances d'une série présentes dans un Orthanc."""
    found = http("POST", f"{base}/tools/lookup", series_uid.encode())
    series = next((f["ID"] for f in found if f["Type"] == "Series"), None)
    return len(http("GET", f"{base}/series/{series}")["Instances"]) if series else 0


def scenario_priorite():
    """Liaison à 20 Mbit/s, étude de 4 séries dont la dernière arrivée (« THORAX », 10 instances) est prioritaire si
    la box A a une règle de priorité : délai d'arrivée complète de cette série chez Orthanc B, et de l'étude entière."""
    rules = http("GET", f"{BOX_A}/api/v1/priority")["rules"]
    events = {}
    counts = [60, 60, 60, 10]

    def watch(transfer):
        thorax = transfer.series_uids[-1]
        deadline = time.monotonic() + 1800
        while time.monotonic() < deadline:
            transfer.poll()
            if "serieThorax_s" not in events and series_count(ORTHANC_B, thorax) >= counts[-1]:
                events["serieThorax_s"] = transfer.elapsed()
                log(f"[priorite] série THORAX complète chez Orthanc B à {events['serieThorax_s']} s")
            if "restored" in transfer.timeline:
                return
            time.sleep(0.5)

    degrade(True)
    try:
        result = full_transfer("priorite", 4, counts, during=watch,
                               descriptions=["ABDOMEN", "PELVIS", "MEMBRES", "THORAX"])
    finally:
        degrade(False)
    result.update(events)
    result["reglesPriorite"] = [f"{r['name']} : {r['priority']}" for r in rules]
    return result


def scenario_coupure(cut_seconds=60):
    """Coupure réseau de la box A pendant 60 s, au milieu du dépôt des paquets sur le relais.

    Tout le trafic de la box A côté relais est bloqué, sauf la passerelle Docker par laquelle le pilote
    interroge la console (ports publiés). Les règles sont retirées quoi qu'il arrive.
    """
    iface = relais_iface("net-a")
    gateway = relais_gateway()
    rules = (f"INPUT -i {iface} ! -s {gateway} -j DROP", f"OUTPUT -o {iface} ! -d {gateway} -j DROP")
    events = {}

    def cut(transfer):
        transfer.wait_for(lambda s: s == "PACKAGED", 600, "empaquetage")
        time.sleep(3)  # dépôt commencé
        events["coupure_s"] = transfer.elapsed()
        netshoot("net-a", " && ".join(f"iptables -I {r}" for r in rules))
        log(f"[coupure] réseau de la box A coupé pour {cut_seconds} s")
        try:
            end = time.monotonic() + cut_seconds
            while time.monotonic() < end:
                transfer.poll()
                time.sleep(1)
        finally:
            netshoot("net-a", " ; ".join(f"iptables -D {r}" for r in rules))
        events["retablissement_s"] = transfer.elapsed()
        log("[coupure] réseau rétabli")

    result = full_transfer("coupure-reseau", 4, 150, during=cut)
    result.update(events)
    if "ANNOUNCED" in result["chronologie_s"]:
        result["annonceApresRetablissement_s"] = round(result["chronologie_s"]["ANNOUNCED"]
                                                       - events["retablissement_s"], 2)
    return result


def inspect_relays(markers):
    """Cherche des données en clair dans tout ce que le relais a écrit sur disque : morceaux et base PostgreSQL."""
    # Les pages modifiées de la base sont écrites sur disque avant l'inspection.
    run("docker", "exec", container("relay-db"), "psql", "-U", "relais", "-d", "relais", "-c", "CHECKPOINT")
    volumes = [f"{PROJECT}_{v}" for v in ("relay-data", "relay-db-data")]
    report = {"fichiers": 0, "octets": 0, "marqueursTrouves": {}, "entropieMin_bits": None,
              "volumes": volumes}
    found = Counter()
    entropies = []
    for volume in volumes:
        root = Path(run("docker", "volume", "inspect", "-f", "{{.Mountpoint}}", volume))
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            data = path.read_bytes()
            report["fichiers"] += 1
            report["octets"] += len(data)
            for name, marker in markers.items():
                if marker in data:
                    found[f"{name} dans {volume}/{path.relative_to(root)}"] += 1
            if volume.endswith("relay-data") and len(data) > 4096:
                entropies.append(entropy(data[: 4 * MO]))
    report["marqueursTrouves"] = dict(found)
    report["morceaux"] = len(entropies)
    if entropies:
        report["entropieMin_bits"] = round(min(entropies), 4)
        report["entropieMoyenne_bits"] = round(sum(entropies) / len(entropies), 4)
    return report


def entropy(data):
    counts = Counter(data)
    total = len(data)
    return -sum(c / total * math.log2(c / total) for c in counts.values())


def scenario_destinataire_eteint(offline_seconds=90):
    """Box B éteinte pendant l'envoi ; inspection du relais pendant l'attente ; box B rallumée."""
    events = {}

    def offline(transfer):
        transfer.wait_for(lambda s: s == "ANNOUNCED", 900, "annonce (dépôt sans destinataire)")
        events["annonce_s"] = transfer.elapsed()
        markers = {"nom du patient": PATIENT_NAME.encode(), "identifiant du patient": b"IU-CAMPAGNE-",
                   "Study Instance UID": transfer.study_uid.encode(), "en-tête DICOM": b"DICM",
                   "message ImagUnity": b"imagunity.transfer"}
        events["inspectionRelais"] = inspect_relays(markers)
        remaining = offline_seconds - (transfer.elapsed() - events["annonce_s"])
        time.sleep(max(remaining, 0))
        events["rallumage_s"] = transfer.elapsed()
        compose("start", "box-b")
        log("[destinataire-eteint] box B rallumée")

    log("[destinataire-eteint] arrêt de la box B")
    compose("stop", "box-b")
    try:
        result = full_transfer("destinataire-eteint", 2, 50, during=offline)
    finally:
        compose("start", "box-b")
    result.update(events)
    if "restored" in result["chronologie_s"]:
        result["restitutionApresRallumage_s"] = round(result["chronologie_s"]["restored"] - events["rallumage_s"], 2)
    return result


def scenario_volumineux(go=2):
    """Examen de plusieurs Go : instances 1024×1024 de 2 Mo, découpé en paquets de 900 Mo au plus."""
    per_series = int(go * 1024 / 2 / 4)
    return full_transfer(f"volumineux-{go}Go", 4, per_series, side=1024, timeout=3600)


SCENARIOS = {
    "nominal": scenario_nominal,
    "pare-feu": scenario_pare_feu,
    "liaison-degradee": scenario_degrade,
    "coupure-reseau": scenario_coupure,
    "destinataire-eteint": scenario_destinataire_eteint,
    "volumineux": scenario_volumineux,
    "liaison-lente": scenario_liaison_lente,
    "coupure-liaison-lente": scenario_coupure_lente,
    "priorite": scenario_priorite,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenarios", nargs="+", choices=[*SCENARIOS, "tout"])
    parser.add_argument("--sortie", type=Path, default=HERE / "resultats")
    args = parser.parse_args()
    names = list(SCENARIOS) if "tout" in args.scenarios else args.scenarios

    ensure_paired()
    results = {"date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
               "transport": "IU-T",
               "versionBox": http("GET", f"{BOX_A}/api/v1/box").get("version"),
               "scenarios": []}
    for name in names:
        log(f"=== {name} ===")
        try:
            results["scenarios"].append(SCENARIOS[name]())
        except Exception as e:  # le scénario suivant doit pouvoir tourner
            log(f"[{name}] ÉCHEC : {e!r}")
            results["scenarios"].append({"scenario": name, "erreur": repr(e)})
    args.sortie.mkdir(parents=True, exist_ok=True)
    out = args.sortie / f"campagne-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    log(f"Résultats : {out}")


if __name__ == "__main__":
    sys.exit(main())
