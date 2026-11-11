/*
 * Uniquement pour la campagne dans un environnement sans IPv6 (noyau sans support IPv6) :
 * les relais SimpleX demandent une adresse d'écoute « AF_UNSPEC » et obtiennent « :: » (IPv6),
 * impossible à ouvrir. Ce module, préchargé par LD_PRELOAD, restreint ces demandes à IPv4.
 * Inutile sur un hôte où IPv6 fonctionne.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <netdb.h>
#include <string.h>
#include <sys/socket.h>

int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res) {
    static int (*real)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
    if (!real) {
        real = (int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))
                dlsym(RTLD_NEXT, "getaddrinfo");
    }
    struct addrinfo copy;
    if (hints && hints->ai_family == AF_UNSPEC) {
        memcpy(&copy, hints, sizeof copy);
        copy.ai_family = AF_INET;
        return real(node, service, &copy, res);
    }
    if (!hints) {
        memset(&copy, 0, sizeof copy);
        copy.ai_family = AF_INET;
        return real(node, service, &copy, res);
    }
    return real(node, service, hints, res);
}
