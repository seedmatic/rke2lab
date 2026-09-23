---
name: incus-bridge-dnsmasq-is-every-pod-first-resolver
description: "Le dnsmasq du bridge vmnet est le PREMIER résolveur de tous les pods du cluster, et il servait le /etc/hosts de l'hôte — donc un nom d'hôte résolvait vers 127.0.0.2 une fois sur deux. Corrigé par no-hosts dans raw.dnsmasq"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T07:29:52.851Z
---

Trouvé le 2026-09-23 en cherchant pourquoi CAPN n'arrivait pas à provisionner `bioskop-wrkld`.
Corrigé par rke2lab `d29edbffd`.

## Le chemin de résolution d'un pod — contre-intuitif

Le kubelet passe aux pods `resolvConf: /run/systemd/resolve/resolv.conf`
(`/var/lib/rancher/rke2/agent/etc/kubelet.conf.d/00-rke2-defaults.conf`), **pas**
`/etc/resolv.conf`. Or ce fichier liste les upstreams par lien, et le premier est
**`10.80.0.1`** — le dnsmasq du bridge vmnet — avant le routeur `192.168.1.254`.

CoreDNS tourne en `dnsPolicy: Default` avec `forward . /etc/resolv.conf`, donc il hérite de ces
quatre serveurs. **Le dnsmasq d'incus est donc le résolveur primaire de tout le cluster.**

⚠️ Et il transmet un nom à **label unique** tel quel — contrairement au `resolved` du nœud, qui
refuse d'interroger pour un nom sans point. D'où l'asymétrie déroutante : `resolvectl query
bioskop-nixos` dit « not found » sur le nœud, alors qu'un pod obtient une réponse.

## La fuite

dnsmasq sert le `/etc/hosts` de l'hôte par défaut (aucun `--no-hosts` dans sa ligne de commande), et
NixOS y écrit `127.0.0.2 <hostname>`. Donc, la recherche par domaine supprimée (point final) :

```
bioskop-nixos.  @10.80.0.1      ->  127.0.0.2      ← le dnsmasq du bridge
bioskop-nixos.  @192.168.1.254  ->  192.168.1.130  ← le routeur
```

Une **course**, avec deux réponses incompatibles. Ce qui a cassé CAPN : sur la réponse loopback il
composait `https://127.0.0.2:8443`, soit son propre `--diagnostics-address=:8443`, dont le
certificat auto-signé de controller-runtime donne le message trompeur *« certificate is valid for
localhost, not bioskop-nixos »*. L'instance était pourtant bien **lancée** (cet appel-là avait gagné
la course) et seul le `GetInstanceState` suivant échouait.

★ La leçon de diagnostic : un `x509: valid for localhost` avec un **port qui appartient au
demandeur** ne dit pas « mauvais certificat », il dit « tu t'es parlé à toi-même ». Vérifier la
résolution avant le TLS.

## Le correctif et ce qui a été écarté

`no-hosts` dans `raw.dnsmasq` (`GrowNetworkResolver.rawDnsmasq`) — on possédait déjà ce levier, qui
ne portait que les lignes `dhcp-host`. Rien n'est perdu : les noms d'instance viennent de
`--dhcp-hostsfile`. Livraison par un simple `pulumi up` (config de réseau incus, **pas** de
recréation d'instance — contrairement à [[node-bootstrap-objects-need-instance-recreation]]).

- ❌ **Rendre un FQDN** (`bioskop-nixos.lan` résout de façon cohérente et est dans les SANs) :
  esquive ce nom-là et laisse la classe armée pour tous les autres.
- ❌ **mDNS / `.local`** (proposé, à juste titre écarté) : `.local` c'est avahi via le **NSS du
  nœud**. Un pod ne passe pas par le NSS du nœud. Le mDNS que `resolvectl` montre activé sur les
  veth `lxc…` est celui du nœud sur ces liens, jamais sur le chemin d'un pod.
- ❌ **Une IP** : les 8 SANs du certificat incus sont tous des noms DNS, aucun `IP Address`.

See [[node-bootstrap-objects-need-instance-recreation]] [[kubeconfig-context-per-cluster-intention]]
[[pulumi-incus-resource-gotchas]].
