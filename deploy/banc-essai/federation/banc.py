#!/usr/bin/env python3
"""Banc d'essai de la fédération : deux organisations sur un même poste, sans Docker.

Chaque organisation a son service d'annuaire, son relais IU-T et une box ; un PACS simulé (pynetdicom) envoie et
reçoit de chaque côté. Le banc :

1. crée les deux clés de responsable, tire un code d'enrôlement par box, démarre tout, enrôle les box ;
2. fait signer à chaque responsable la reconnaissance de l'autre (périmètre en groupes, relais autorisés) ;
3. appaire les box par l'annuaire, envoie un examen synthétique de A vers B puis de B vers A, et vérifie qu'il arrive
   intact au PACS destinataire, accusé compris ;
4. vérifie les preuves de fraîcheur des deux annuaires (clés déléguées par les responsables) ;
5. vérifie les refus : box hors périmètre, relais non autorisé, accord à sens unique, identifiant de box revendiqué
   par l'autre organisation, annuaire du partenaire figé, accord révoqué.

Données synthétiques uniquement. Prérequis : jars construits (cd backend && mvn -DskipTests package), Java 25,
python3 avec pydicom et pynetdicom (pip install -r requirements.txt). Les bases sont en H2 (fichier) : le jar H2 est
pris dans le dépôt Maven local, ou dans H2_JAR.

    python3 banc.py [--travail DOSSIER] [--garder]
"""

import argparse
import base64
import glob
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import CTImageStorage, ExplicitVRLittleEndian, generate_uid
from pynetdicom import AE, StoragePresentationContexts, evt

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BACKEND = ROOT / "backend"
JAVA = os.environ.get("JAVA", str(Path(os.environ.get("JAVA_HOME", "/usr")) / "bin" / "java"))
PHRASE = "phrase synthétique du responsable"
CONSOLE = ("operateur", "essai-synthetique")
INTERVAL = "5s"
FRESHNESS_MAX_AGE = "90s"   # court pour l'essai (48 h par défaut)
LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"

ORGS = {
    "A": {"id": "grand-est", "annuaire": 8090, "relais": 8443, "box": 8081, "dicom": 11112, "pacs": 11114,
          "groupe": "imagerie", "vers": "IU_TO_B", "partenaire": "box-b"},
    "B": {"id": "bourgogne-fc", "annuaire": 8091, "relais": 8444, "box": 8082, "dicom": 11113, "pacs": 11115,
          "groupe": "neuro", "vers": "IU_TO_A", "partenaire": "box-a"},
}
COMPTE = "responsable:depot-synthetique"
LECTEUR = "lecteur:lecture-synthetique"   # compte de lecture du relais (lecture réservée)

PROCESSES = []
RESULTS = []


def log(message):
    print(f"[{datetime.now():%H:%M:%S}] {message}", flush=True)


def result(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    log(("RÉUSSI  " if ok else "ÉCHEC   ") + name + (f" : {detail}" if detail else ""))


def wait(condition, timeout, what, step=1.0):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = condition()
            if value:
                return value
        except (urllib.error.URLError, OSError, KeyError, ValueError) as e:
            last = e
        time.sleep(step)
    raise TimeoutError(f"Délai dépassé : {what}" + (f" ({last})" if last else ""))


def http(method, url, body=None, auth=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if auth:
        request.add_header("Authorization", "Basic " + base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode())
        request.add_header("X-ImagUnity-Console", "1")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        content = response.read()
        return json.loads(content) if content else None


def console(org, method, path, body=None):
    return http(method, f"http://127.0.0.1:{ORGS[org]['box']}{path}", body, CONSOLE)


# --- Lancement des composants --------------------------------------------------

def h2_jar():
    if os.environ.get("H2_JAR"):
        return os.environ["H2_JAR"]
    jars = sorted(glob.glob(str(Path.home() / ".m2/repository/com/h2database/h2/*/h2-*.jar")))
    if not jars:
        sys.exit("Jar H2 introuvable : le donner dans H2_JAR")
    return jars[-1]


def start(name, args, env, work):
    logfile = open(work / "journaux" / f"{name}.log", "w")
    full = dict(os.environ, LANG="C.UTF-8", **env)
    process = subprocess.Popen(args, cwd=work, env=full, stdout=logfile, stderr=subprocess.STDOUT,
                               start_new_session=True)
    PROCESSES.append(process)
    return process


def stop_all():
    for process in PROCESSES:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    for process in PROCESSES:
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)


def admin(work, org, *args, check=True):
    env = dict(os.environ, LANG="C.UTF-8", IMAGUNITY_PHRASE_ORGANISATION=PHRASE, IMAGUNITY_ANNUAIRE_COMPTE=COMPTE)
    command = [JAVA, "-Dstdout.encoding=UTF-8", "-cp", str(BACKEND / "directory-server/target/imagunity-directory.jar"),
               "-Dloader.main=com.imagunity.directory.DirectoryAdmin", LAUNCHER, *args]
    done = subprocess.run(command, cwd=work / org, env=env, capture_output=True, text=True)
    if check and done.returncode != 0:
        raise RuntimeError(f"admin {' '.join(args)} : {done.stdout}{done.stderr}")
    return done.stdout + done.stderr


def annuaire_url(org):
    return f"http://127.0.0.1:{ORGS[org]['annuaire']}"


def relais_url(org):
    return f"http://127.0.0.1:{ORGS[org]['relais']}"


def start_directory(work, org, h2):
    o = ORGS[org]
    return start(f"annuaire-{org}", [JAVA, f"-Dloader.path={h2}", "-cp",
                              str(BACKEND / "directory-server/target/imagunity-directory.jar"), LAUNCHER], {
        "IMAGUNITY_DIRECTORY_PORT": str(o["annuaire"]),
        "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY": str(work / org / "organisation.json.pub.json"),
        "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE": o["empreinte"],
        "IMAGUNITY_DIRECTORY_PUBLISHER_PASSWORD": COMPTE.split(":")[1],
        "IMAGUNITY_DIRECTORY_DB_URL": f"jdbc:h2:file:{work / org / 'annuaire'};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "IMAGUNITY_DIRECTORY_DB_USER": "sa",
        "IMAGUNITY_DIRECTORY_PARTNER_SYNC_INTERVAL": INTERVAL,
        "IMAGUNITY_DIRECTORY_FRESHNESS_KEY": str(work / org / "fraicheur.json"),
        "IMAGUNITY_DIRECTORY_FRESHNESS_DELEGATION": str(work / org / "delegation-fraicheur.json"),
        "IMAGUNITY_DIRECTORY_FRESHNESS_INTERVAL": "20s",
        "IMAGUNITY_DIRECTORY_RESTRICTED_READ": "true",
        "IMAGUNITY_DIRECTORY_READER_PASSWORD": LECTEUR.split(":")[1],
    }, work)


def start_relay(work, org, h2):
    o = ORGS[org]
    start(f"relais-{org}", [JAVA, f"-Dloader.path={h2}", "-cp",
                            str(BACKEND / "relay/target/imagunity-relay-exec.jar"), LAUNCHER], {
        "IMAGUNITY_RELAY_PORT": str(o["relais"]),
        "IMAGUNITY_RELAY_STORAGE_DIR": str(work / org / "morceaux"),
        "IMAGUNITY_RELAY_INSTANCE": f"relais-{org}",
        "IMAGUNITY_RELAY_DIRECTORY_URL": annuaire_url(org),
        "IMAGUNITY_RELAY_ORGANIZATION_KEY": str(work / org / "organisation.json.pub.json"),
        "IMAGUNITY_RELAY_ORGANIZATION_CODE": ORGS[org]["empreinte"],
        "IMAGUNITY_RELAY_DIRECTORY_SYNC_INTERVAL": INTERVAL,
        "IMAGUNITY_RELAY_DIRECTORY_ACCOUNT": LECTEUR,
        "IMAGUNITY_RELAY_DIRECTORY_FRESHNESS_MAX_AGE": FRESHNESS_MAX_AGE,
        "IMAGUNITY_RELAY_DB_URL": f"jdbc:h2:file:{work / org / 'relais'};MODE=PostgreSQL",
        "IMAGUNITY_RELAY_DB_USER": "sa",
    }, work)


def start_box(work, org, code):
    o = ORGS[org]
    data = work / org / "box"
    data.mkdir(parents=True, exist_ok=True)
    start(f"box-{org}", [JAVA, "-Dstdout.encoding=UTF-8", "-jar", str(BACKEND / "app/target/imagunity-box.jar")], {
        "IMAGUNITY_BOX_NAME": f"Box {org} (nom local)",
        "SERVER_ADDRESS": "127.0.0.1",
        "IMAGUNITY_HTTP_PORT": str(o["box"]),
        "IMAGUNITY_DICOM_BIND_ADDRESS": "127.0.0.1",
        "IMAGUNITY_DICOM_PORT": str(o["dicom"]),
        "IMAGUNITY_DATA_DIR": str(data),
        "IMAGUNITY_CONSOLE_USER": CONSOLE[0],
        "IMAGUNITY_CONSOLE_PASSWORD": CONSOLE[1],
        "IMAGUNITY_DICOM_ALLOWED_CALLING_AETS": f"PACS_{org}",
        "IMAGUNITY_DICOM_ROUTES_0_CALLED_AET": o["vers"],
        "IMAGUNITY_DICOM_ROUTES_0_DESTINATION": o["partenaire"],
        "IMAGUNITY_PACS_HOST": "127.0.0.1",
        "IMAGUNITY_PACS_PORT": str(o["pacs"]),
        "IMAGUNITY_PACS_AET": f"PACS_{org}",
        "IMAGUNITY_BOX_AET": f"IU_{org}",
        "IMAGUNITY_DIRECTORY_ENABLED": "true",
        "IMAGUNITY_DIRECTORY_URL": annuaire_url(org),
        "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY": str(work / org / "organisation.json.pub.json"),
        "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE": o["empreinte"],
        "IMAGUNITY_DIRECTORY_SYNC_INTERVAL": INTERVAL,
        "IMAGUNITY_DIRECTORY_FRESHNESS_MAX_AGE": FRESHNESS_MAX_AGE,
        "IMAGUNITY_DIRECTORY_ENROLLMENT_CODE": code,
        "IMAGUNITY_IU_RELAY": relais_url(org),
    }, work)


def up(url):
    try:
        urllib.request.urlopen(url, timeout=3).read()
        return True
    except urllib.error.HTTPError:
        return True
    except (urllib.error.URLError, OSError):
        return False


# --- PACS simulés --------------------------------------------------------------

def start_pacs(work, org):
    """PACS destinataire : SCP de stockage, garde chaque instance reçue (empreinte des pixels)."""
    received = {}
    target = work / org / "pacs"
    target.mkdir(parents=True, exist_ok=True)

    def on_store(event):
        ds = event.dataset
        ds.file_meta = event.file_meta
        received[ds.SOPInstanceUID] = hashlib.sha256(ds.PixelData).hexdigest()
        ds.save_as(target / f"{ds.SOPInstanceUID}.dcm", enforce_file_format=True)
        return 0x0000

    ae = AE(ae_title=f"PACS_{org}")
    ae.supported_contexts = StoragePresentationContexts
    ae.start_server(("127.0.0.1", ORGS[org]["pacs"]), block=False, evt_handlers=[(evt.EVT_C_STORE, on_store)])
    return received


def make_study(instances, label):
    study_uid = generate_uid()
    series_uid = generate_uid()
    datasets = []
    for i in range(1, instances + 1):
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
        ds.PatientName = "SYNTHETIQUE^FEDERATION"
        ds.PatientID = f"IU-FEDERATION-{label}"
        ds.StudyDescription = f"Banc fédération {label}"
        ds.Modality = "CT"
        ds.SeriesNumber = 1
        ds.InstanceNumber = i
        ds.Rows = ds.Columns = 128
        ds.SamplesPerPixel = 1
        ds.PhotometricInterpretation = "MONOCHROME2"
        ds.BitsAllocated = ds.BitsStored = 16
        ds.HighBit = 15
        ds.PixelRepresentation = 0
        ds.PixelData = os.urandom(128 * 128 * 2)
        datasets.append(ds)
    return study_uid, datasets


def send_study(org, datasets):
    """PACS émetteur : C-STORE vers la box de son organisation, à l'AET qui désigne la box partenaire."""
    ae = AE(ae_title=f"PACS_{org}")
    ae.add_requested_context(CTImageStorage, ExplicitVRLittleEndian)
    assoc = ae.associate("127.0.0.1", ORGS[org]["dicom"], ae_title=ORGS[org]["vers"])
    if not assoc.is_established:
        raise RuntimeError(f"Association refusée par la box {org}")
    try:
        for ds in datasets:
            status = assoc.send_c_store(ds)
            if not status or status.Status != 0x0000:
                raise RuntimeError(f"C-STORE refusé par la box {org} : {status}")
    finally:
        assoc.release()


def transfer_of(org, study_uid):
    for t in console(org, "GET", "/api/v1/transfers?limit=50"):
        if t["studyInstanceUid"] == study_uid:
            return t
    return None


def exchange(sender, receiver, received, label, instances=5):
    study_uid, datasets = make_study(instances, label)
    expected = {ds.SOPInstanceUID: hashlib.sha256(ds.PixelData).hexdigest() for ds in datasets}
    started = time.monotonic()
    send_study(sender, datasets)
    wait(lambda: (transfer_of(sender, study_uid) or {}).get("state") in ("ACKNOWLEDGED", "FAILED"), 240,
         f"transfert {label} accusé ou en échec", 2)
    transfer = transfer_of(sender, study_uid)
    arrived = {uid: received.get(uid) for uid in expected}
    intact = arrived == expected
    result(f"Examen {label} : {sender} → {receiver}", transfer["state"] == "ACKNOWLEDGED" and intact,
           f"{transfer['state']}, {sum(1 for v in arrived.values() if v)} / {instances} instances intactes au PACS "
           f"{receiver}, {time.monotonic() - started:.0f} s" + (f", {transfer['errorMessage']}"
                                                                   if transfer.get("errorMessage") else ""))
    return transfer


def refused_send(sender, label, expected_reason):
    """Un envoi qui doit échouer, avec un motif contenant expected_reason."""
    study_uid, datasets = make_study(1, label)
    send_study(sender, datasets)
    transfer = wait(lambda: (lambda t: t if t and t["state"] == "FAILED" else None)(transfer_of(sender, study_uid)),
                    120, f"échec du transfert {label}", 2)
    reason = transfer.get("errorMessage") or ""
    result(f"Refus : {label}", expected_reason in reason, reason)


def box_view(viewer, other_id):
    return next((b for b in console(viewer, "GET", "/api/v1/directory")["boxes"] if b["boxId"] == other_id), None)


def recognize(work, org, partner, local_groups, partner_groups, relays=None):
    admin(work, org, "reconnaitre", "--cles", "organisation.json", "--annuaire", annuaire_url(org),
          "--partenaire", str(work / partner / "organisation.json.pub.json"), "--annuaire-partenaire",
          annuaire_url(partner), "--relais", relays or relais_url(partner), "--groupes-locaux", local_groups,
          "--groupes-partenaire", partner_groups, "--confirmer")


# --- Déroulé -------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--travail", default=str(HERE / "travail"), help="dossier de travail (effacé)")
    parser.add_argument("--garder", action="store_true", help="laisser tourner les composants à la fin")
    options = parser.parse_args()
    work = Path(options.travail).resolve()
    shutil.rmtree(work, ignore_errors=True)
    (work / "journaux").mkdir(parents=True)
    h2 = h2_jar()
    try:
        run(work, h2)
    finally:
        report(work)
        if options.garder:
            log("Composants laissés en marche (--garder) ; arrêt par Ctrl+C")
            try:
                signal.pause()
            except KeyboardInterrupt:
                pass
        stop_all()
    sys.exit(0 if RESULTS and all(ok for _, ok, _ in RESULTS) else 1)


def run(work, h2):
    codes = {}
    for org, o in ORGS.items():
        (work / org).mkdir()
        out = admin(work, org, "organisation-creer", "--organisation", o["id"], "--cles", "organisation.json")
        o["empreinte"] = out.split("hors bande : ")[1].split()[0]
        admin(work, org, "fraicheur-deleguer", "--cles", "organisation.json", "--sortie-cle", "fraicheur.json",
              "--sortie-delegation", "delegation-fraicheur.json")
        out = admin(work, org, "code-creer", "--codes", "codes.json", "--nom", f"Box {org}", "--groupes", o["groupe"])
        codes[org] = out.split("IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=")[1].split()[0]
        log(f"Organisation {org} « {o['id']} » : empreinte {o['empreinte']}")

    directories = {}
    for org in ORGS:
        directories[org] = start_directory(work, org, h2)
    for org in ORGS:
        wait(lambda: up(annuaire_url(org) + "/directory/v1/organization"), 120, f"annuaire {org}")
        start_relay(work, org, h2)
    for org in ORGS:
        wait(lambda: up(relais_url(org) + "/actuator/health"), 120, f"relais {org}")
        start_box(work, org, codes[org])
    received = {org: start_pacs(work, org) for org in ORGS}

    # Enrôlement automatique dans chaque organisation.
    box_ids = {}
    for org in ORGS:
        wait(lambda: up(f"http://127.0.0.1:{ORGS[org]['box']}/api/v1/box"), 180, f"box {org}")
        box_ids[org] = console(org, "GET", "/api/v1/directory")["boxId"]
        wait(lambda: f"Box {org}" in admin(work, org, "enroler-attente", "--cles", "organisation.json", "--codes",
                                           "codes.json", "--annuaire", annuaire_url(org), "--confirmer"),
             120, f"demande d'enrôlement de la box {org}", 3)
        wait(lambda: (box_view(org, box_ids[org]) or {}).get("reachable"), 120, f"box {org} active et joignable")
    result("Enrôlement des deux box", True, f"A = {box_ids['A']}, B = {box_ids['B']}")

    # Versions du protocole : annoncées par la box dans sa demande, recopiées dans son entrée signée.
    announced = {org: json.loads(http("GET", annuaire_url(org) + f"/directory/v1/entries/{box_ids[org]}")["payload"])
                 .get("protocols") for org in ORGS}
    result("Versions du protocole annoncées", all(v == ["iu1.0"] for v in announced.values()),
           f"entrées signées : A {announced['A']}, B {announced['B']}")

    # Reconnaissance mutuelle : groupes des deux box, relais de chaque organisation.
    recognize(work, "A", "B", "imagerie", "neuro")
    recognize(work, "B", "A", "neuro", "imagerie")
    wait(lambda: (lambda b: b and b["organization"] == "bourgogne-fc" and b["reachable"] and not b["refusal"])(
        box_view("A", box_ids["B"])), 120, "box B vue par A, dans l'accord")
    wait(lambda: (lambda b: b and b["organization"] == "grand-est" and not b["refusal"])(
        box_view("B", box_ids["A"])), 120, "box A vue par B, dans l'accord")
    result("Reconnaissance mutuelle", True, "chaque box voit l'autre comme box partenaire utilisable")

    # Preuves de fraîcheur : celle de son annuaire, et celle du partenaire relayée par son annuaire.
    def fresh(view):
        partners = view["partners"]
        return view["freshness"] and view["freshness"]["issuedAt"] and partners \
            and partners[0]["freshness"] and partners[0]["freshness"]["issuedAt"]
    wait(lambda: fresh(console("A", "GET", "/api/v1/directory")) and fresh(console("B", "GET", "/api/v1/directory")),
         120, "preuves de fraîcheur des deux annuaires vues par chaque box")
    checks = [admin(work, org, "fraicheur-verifier", "--cles", "organisation.json", "--annuaire", annuaire_url(org))
              for org in ORGS]
    result("Preuves de fraîcheur", all("correspond" in c for c in checks),
           "chaque box a la preuve de son annuaire et celle du partenaire ; fraicheur-verifier : preuves conformes")

    # Lecture réservée : listes refusées sans compte ni signature ; box, relais et annuaires partenaires y lisent.
    def status(url, auth=None):
        try:
            http("GET", url, auth=auth)
            return 200
        except urllib.error.HTTPError as e:
            return e.code
    closed = [status(annuaire_url(org) + path) for org in ORGS
              for path in ("/directory/v1/entries", "/directory/v1/recognitions")]
    public = [status(annuaire_url(org) + f"/directory/v1/entries/{box_ids[org]}") for org in ORGS]
    reader = [status(annuaire_url(org) + "/directory/v1/entries?since=0", tuple(LECTEUR.split(":"))) for org in ORGS]
    result("Lecture réservée des annuaires", set(closed) == {401} and set(public) == {200} and set(reader) == {200},
           f"listes sans compte : {sorted(set(closed))}, entrée d'une box : {sorted(set(public))}, compte de lecture : "
           f"{sorted(set(reader))} ; box, relais et annuaire partenaire lisent par lecture signée ou compte")

    console("A", "POST", f"/api/v1/directory/boxes/{box_ids['B']}/pair", {"name": "box-b"})
    wait(lambda: any(p.get("boxId") == box_ids["B"] for p in console("A", "GET", "/api/v1/peers"))
         and any(p.get("boxId") == box_ids["A"] for p in console("B", "GET", "/api/v1/peers")), 120,
         "appairage et poignée de main entre organisations")
    names = {p["name"] for p in console("B", "GET", "/api/v1/peers")}
    result("Appairage entre organisations", "box-a" in names, f"partenaires vus par B : {sorted(names)}")

    exchange("A", "B", received["B"], "A-vers-B")
    exchange("B", "A", received["A"], "B-vers-A")

    # Refus.
    recognize(work, "A", "B", "urgences", "neuro")
    wait(lambda: "pas dans les groupes" in ((box_view("A", box_ids["B"]) or {}).get("refusal") or ""), 60,
         "périmètre réduit vu par A")
    refused_send("A", "box locale hors du périmètre", "pas dans les groupes autorisés")
    recognize(work, "A", "B", "imagerie", "neuro", relays="http://127.0.0.1:9999")
    wait(lambda: "relais non autorisé" in (console("A", "GET", "/api/v1/directory") and
                                         next((b.get("refusal") or "" for b in [box_view("A", box_ids["B"]) or {}]),
                                              "")) or box_view("A", box_ids["B"]) is None, 60,
         "relais de B non autorisé vu par A")
    refused_send("A", "relais du partenaire non autorisé", "relais non autorisé")
    recognize(work, "A", "B", "imagerie", "neuro")
    wait(lambda: not ((box_view("A", box_ids["B"]) or {"refusal": "?"}).get("refusal")), 60, "accord rétabli")

    # Accord à sens unique : B révoque, A envoie encore ; le relais de B refuse, A le signale.
    fingerprint_a = admin(work, "B", "reconnaissances", "--cles", "organisation.json", "--annuaire",
                          annuaire_url("B")).split("empreinte ")[1].split()[0]
    admin(work, "B", "reconnaissance-revoquer", "--cles", "organisation.json", "--annuaire", annuaire_url("B"),
          "--empreinte", fingerprint_a, "--motif", "essai d'accord à sens unique", "--confirmer")
    time.sleep(15)
    study_uid, datasets = make_study(1, "sens-unique")
    send_study("A", datasets)
    journal = work / "journaux" / "box-A.log"
    wait(lambda: "accord à sens unique" in journal.read_text(errors="replace"), 120,
         "avertissement « accord à sens unique » de la box A")
    result("Refus : accord à sens unique", True, "dépôt refusé par le relais de B, signalé par la box A, message gardé")
    recognize(work, "B", "A", "neuro", "imagerie")

    # Identifiant de box revendiqué : le responsable de B publie une entrée sous l'identifiant de la box A.
    admin(work, "B", "box-demande", "--cles", "usurpatrice.json", "--box-id", box_ids["A"], "--nom", "Usurpatrice",
          "--iu", relais_url("B") + "/iu/v1/queues/" + "u" * 43, "--sortie", "demande-usurpatrice.json",
          "--sans-phrase")
    admin(work, "B", "enroler", "--cles", "organisation.json", "--demande", "demande-usurpatrice.json",
          "--groupes", "neuro", "--sortie", "entree-usurpatrice.json")
    admin(work, "B", "publier", "--annuaire", annuaire_url("B"), "--entree", "entree-usurpatrice.json")
    collisions = wait(lambda: console("A", "GET", "/api/v1/directory")["collisions"], 90,
                      "identifiant revendiqué signalé par A")
    own = box_view("A", box_ids["A"])
    result("Refus : identifiant de box revendiqué par l'autre organisation",
           own is not None and own["organization"] is None,
           f"{collisions[-1]['claimant']} écartée, tenu par {collisions[-1]['holder']}")

    # Annuaire du partenaire figé : l'annuaire B s'arrête ; passé l'âge maximal, A refuse d'envoyer vers B.
    os.killpg(directories["B"].pid, signal.SIGTERM)
    directories["B"].wait(timeout=60)
    wait(lambda: "fraîcheur" in ((box_view("A", box_ids["B"]) or {}).get("refusal") or ""), 240,
         "annuaire de B vu figé par A", 5)
    refused_send("A", "annuaire du partenaire figé", "sans preuve de fraîcheur récente")

    # Accord révoqué par A : plus d'envoi vers B.
    fingerprint_b = admin(work, "A", "reconnaissances", "--cles", "organisation.json", "--annuaire",
                          annuaire_url("A")).split("empreinte ")[1].split()[0]
    admin(work, "A", "reconnaissance-revoquer", "--cles", "organisation.json", "--annuaire", annuaire_url("A"),
          "--empreinte", fingerprint_b, "--motif", "fin de l'essai", "--confirmer")
    wait(lambda: box_view("A", box_ids["B"]) is None, 60, "box B retirée des box utilisables par A")
    refused_send("A", "accord révoqué", "révoqué")


def report(work):
    lines = [f"# Banc fédération — {datetime.now():%Y-%m-%d %H:%M}", "", "| Essai | Résultat | Détail |",
             "|---|---|---|"]
    for name, ok, detail in RESULTS:
        lines.append(f"| {name} | {'réussi' if ok else 'ÉCHEC'} | {detail.replace('|', '/')} |")
    text = "\n".join(lines) + "\n"
    (work / "resultats.md").write_text(text)
    print("\n" + text)


if __name__ == "__main__":
    main()
