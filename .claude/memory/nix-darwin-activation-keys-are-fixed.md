---
name: nix-darwin-activation-keys-are-fixed
description: "Sous nix-darwin, inventer un nom dans system.activationScripts est un NO-OP SILENCIEUX — seule une liste fixe est concaténée ; côté NixOS les noms libres marchent. Passer par postActivation.text + lib.mkAfter"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T16:33:01.524Z
---

Mesuré le 2026-09-23 sur bioskop, après un `darwin-rebuild switch` qui « réussissait » sans rien
faire.

```nix
# ❌ nix-darwin : type-check OK, apparaît dans l'attrset, JAMAIS exécuté
system.activationScripts.baremetalLink.text = "...";

# ✅ nix-darwin
system.activationScripts.postActivation.text = lib.mkAfter "...";
```

★ **Asymétrie NixOS / nix-darwin.** Sous NixOS les noms libres fonctionnent — d'où le piège :
`ndh/modules/nixos/` en contient plusieurs (`incusUserConfig`, `sshGroupKeys`, `hmStateDirs`), donc
grepper le dépôt « est-ce que des noms libres sont utilisés ? » répond **oui à tort** si on ne
sépare pas les deux plateformes. Tous les modules **darwin** de ndh passent par `postActivation`
(18 occurrences) ou `preActivation` (5).

## Comment le prouver (la sonde compte)

`config.system.activationScripts.<monNom>` **existe** — ça ne prouve rien. Et
`config.system.activationScripts.script` est une entrée **sœur**, pas l'agrégat : y chercher son
script donne 0 occurrence même quand le câblage est bon. La bonne sonde :

```
nix eval --raw '.#darwinConfigurations.<h>.config.system.activationScripts.postActivation.text' \
  | grep <mon-marqueur>
```

Et le signe côté runtime, dans `/tmp/darwin-rebuild.log` :
`.activationScripts.postActivation.<nom>:run:<timestamp>`.

⚠️ Corollaire : `postActivation` tourne **après** `etc`, ce qui est en général ce qu'on veut — un
script qui dépend d'un fichier posé par `environment.etc` ne doit pas s'exécuter plus tôt.

See [[darwin-nic-service-not-device]] [[fabric-segment-pinning-and-federation-from-nnh]].
