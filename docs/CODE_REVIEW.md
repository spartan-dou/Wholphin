# Revue de code — Wholphin (fork Shield)

- **Dépôt analysé** : `spartan-dou/Wholphin`, branche par défaut `shield-fix` (commit `560aa682`).
- **Référence amont** : `upstream/main`. Le fork a 23 commits propres et 2 commits amont de retard.
- **Date** : 2026-11-14.
- **Périmètre** : lecture ciblée des zones à risque (CI et build, mise à jour intégrée, stockage des identifiants, réseau, Seerr/Radarr/Sonarr, lecture média, base Room). Le projet compte environ 490 fichiers Kotlin et 95 000 lignes : l'interface Compose (cartes, pages de détail, recherche, TV en direct) n'a été que survolée.
- **Méthode** : lecture du code, `git diff upstream/main...origin/shield-fix`, et quelques vérifications en lecture seule sur le fork avec `gh` (secrets Actions, exécutions du workflow de synchronisation, releases). Rien n'a été compilé ni exécuté : les constats de comportement à l'exécution sont signalés comme tels.

Chemins abrégés : `…/` = `app/src/main/java/com/github/damontecres/wholphin/`.

## Résumé

Aucun constat de sévérité critique. Les cinq points les plus importants :

1. **Élevée — Les releases publiées sont signées avec une clé publique.** Le keystore `app/shield-debug.keystore` est versionné dans un dépôt public avec le mot de passe `android` (`app/build.gradle.kts:95-100`), il sert de repli pour le build release (`app/build.gradle.kts:142-144`), et aucun secret de signature n'est configuré sur le fork. N'importe qui peut donc produire un APK qu'Android acceptera comme mise à jour de l'application installée.
2. **Élevée — La synchronisation nocturne avec l'amont échoue depuis cinq jours sans alerte.** Le rebase bute sur un conflit dans `PlaylistCreator.kt`, et l'issue censée prévenir ne peut pas être créée car les issues sont désactivées sur le fork (`.github/workflows/sync-upstream.yml:63-68`).
3. **Élevée — Le fork occupe la version 42 du schéma Room alors que l'amont est à 41** (`…/data/AppDatabase.kt:56`). La prochaine migration amont portera le même numéro avec un schéma différent : conflit de rebase garanti, et les appareils déjà en « 42 fork » ne recevront pas la migration amont.
4. **Moyenne — Tous les identifiants sont stockés en clair et inclus dans les sauvegardes Android** : jeton Jellyfin, code PIN, mot de passe Seerr, cookie de session Seerr, et désormais les clés API Radarr/Sonarr (droits d'administration complets) ajoutées par le fork. `allowBackup="true"` sans règle d'exclusion (`app/src/main/AndroidManifest.xml:42`).
5. **Moyenne — La reprise automatique sur erreur réseau peut boucler indéfiniment** (`…/ui/playback/PlaybackViewModel.kt:1143` et `:1453-1464`), et le workflow `shield-build.yml` publie une release marquée « latest » sans lancer les tests unitaires (`.github/workflows/shield-build.yml:45`).

## 1. Commits propres au fork

Le fork ajoute 76 fichiers modifiés, environ 4 200 lignes : pipeline de release pour la Shield, réglages mémoire et audio du lecteur, connexion Seerr par Quick Connect, import Radarr/Sonarr et ligne d'accueil « prochaines sorties », panneau d'informations de lecture. Le code est soigné, commenté sur le « pourquoi », et accompagné de tests unitaires (`TestArrCalendar`, `TestArrImport`, `TestPlaybackErrorPolicy`, `ReleaseLabelTests`, `TestDisplayUnsupportedRangeTypes`). Les constats ci-dessous concernent ce code spécifique.

### F1 — Releases signées avec un keystore public — Élevée

- `app/build.gradle.kts:95-100` : configuration `shieldDebug` avec `storePassword = "android"` et `keyPassword = "android"`.
- `app/build.gradle.kts:142-144` : si aucune clé privée n'est fournie, le build **release** retombe sur cette clé.
- `app/shield-debug.keystore` est suivi par git alors que `.gitignore` exclut `*.keystore` : il a été ajouté de force.
- Vérifié : `gh secret list` ne renvoie aucun secret sur le fork, donc `KEY_ALIAS` est vide dans `.github/workflows/shield-build.yml:39-42` et les releases `shield-17` à `shield-19` sont signées avec cette clé. Le dépôt est public.

Conséquence : la signature ne prouve plus l'origine de l'APK. Un APK modifié, signé avec la même clé et doté d'un `versionCode` supérieur, s'installe par-dessus l'application et hérite de ses données (jetons, clés API). Il faut encore amener l'APK sur l'appareil (URL de mise à jour modifiée, installation manuelle, autre application), ce qui limite l'exposition.

Recommandation : générer une clé privée, la placer dans les secrets `SIGNING_KEY`, `KEY_ALIAS`, `KEY_PASSWORD`, `KEY_STORE_PASSWORD` (le workflow les lit déjà), et faire échouer le build release en CI quand ils manquent au lieu de retomber sur la clé partagée. Garder la clé partagée pour le seul type `debug`. Le changement de clé impose une désinstallation unique sur la Shield.

### F2 — Synchronisation nocturne cassée et alerte perdue — Élevée

- Vérifié avec `gh run list` : le workflow « Sync upstream » échoue chaque jour depuis le 2026-11-10. Le journal indique un conflit dans `…/services/PlaylistCreator.kt` au moment de rejouer le commit `efa6022b`.
- `.github/workflows/sync-upstream.yml:63-68` : en cas de conflit, le script ouvre une issue. Or `hasIssuesEnabled` vaut `false` sur le fork : la commande échoue et ne laisse qu'un avertissement dans le journal. Le commentaire du script explique justement qu'une exécution rouge passait inaperçue.

Recommandation : activer les issues sur le fork, ou remplacer l'alerte par un canal qui fonctionne (notification d'échec du workflow, résumé dans `$GITHUB_STEP_SUMMARY`, webhook). Résoudre le conflit en cours à la main. Pour réduire la fréquence des conflits, voir D1.

### F3 — Numéro de version du schéma Room en collision avec l'amont — Élevée

- `…/data/AppDatabase.kt:56` et `:77` : le fork passe à `version = 42` avec `AutoMigration(41, 42)` pour la table `arr_servers` (`app/schemas/.../42.json`). L'amont est à 41.

Dès que l'amont publiera sa propre version 42, le rebase entrera en conflit sur `AppDatabase.kt` et `42.json`. Plus grave, une Shield déjà en version 42 « fork » ne rejouera pas la migration amont 41→42 : Room considérera le schéma à jour puis plantera à la vérification d'identité du schéma.

Recommandation : sortir les tables du fork de la base amont. Une seconde base Room dédiée (par exemple `wholphin_shield`) contenant `arr_servers` supprime toute collision. La clé étrangère vers `users` disparaît : il faut alors purger les lignes à la suppression d'un utilisateur. À défaut, prévoir une migration manuelle à chaque nouvelle version amont.

### F4 — Clés API Radarr/Sonarr en clair — Moyenne

- `…/data/model/ArrServer.kt:31` : `apiKey` stockée telle quelle dans Room.
- `…/services/ArrImportService.kt:58-103` : les clés sont récupérées depuis les réglages Seerr puis enregistrées.
- `…/services/ArrCalendarService.kt:154` : envoi dans l'en-tête `X-Api-Key`, y compris en HTTP simple quand `useSsl` est faux.

Une clé Radarr/Sonarr donne un contrôle complet du service (suppression de médias, modification des clients de téléchargement), pour un besoin qui se limite à lire un calendrier. Voir S1 pour le traitement commun. Hypothèse à vérifier côté Sonarr/Radarr : ces services ne proposent pas de clé en lecture seule, le chiffrement local et l'exclusion des sauvegardes sont donc les seules protections disponibles.

### F5 — La reprise réseau peut boucler sans fin — Moyenne

- `…/ui/playback/PlaybackViewModel.kt:1453-1464` : jusqu'à `MAX_NETWORK_RETRIES` (3) reprises sur erreur réseau transitoire.
- `…/ui/playback/PlaybackViewModel.kt:1143` : le compteur est remis à zéro à chaque `STATE_READY`.
- `…/ui/playback/PlaybackErrorPolicy.kt:24` : `ERROR_CODE_IO_UNSPECIFIED` est classé comme transitoire.

Si l'erreur se reproduit toujours au même endroit du flux (fichier tronqué, plage illisible côté serveur), le lecteur repasse en `READY` sur les données déjà tamponnées, le compteur repart de zéro, puis l'erreur revient : la limite de 3 n'est jamais atteinte et le repli vers le transcodage n'a jamais lieu. Raisonnement sur le code, non reproduit sur appareil.

Recommandation : ne remettre le compteur à zéro qu'après une durée de lecture effective (par exemple 30 secondes de progression), ou compter les reprises par position. Ajouter le cas dans `TestPlaybackErrorPolicy`.

### F6 — Publication automatique sans tests — Moyenne

- `.github/workflows/shield-build.yml:45` : seul `assembleDefaultRelease` est lancé, puis la release est créée avec `--latest` (`:56-59`).
- `.github/workflows/sync-upstream.yml:76` et `:82` : après un rebase réussi, la branche est poussée de force et le build est déclenché.

Chaque nuit, le code amont du jour est donc fusionné, compilé et proposé à la mise à jour intégrée sans qu'aucun test ne tourne. Les tests unitaires n'existent que dans `pr.yml`, qui ne se déclenche que sur les pull requests.

Recommandation : ajouter `testDefaultDebugUnitTest` avant la publication dans `shield-build.yml`, et dans `sync-upstream.yml` avant le `git push --force-with-lease`, afin qu'un rebase qui casse les tests ne remplace pas la branche.

### F7 — Notes de version introuvables — Faible

- `…/services/UpdateChecker.kt:131-132` : l'URL est `releases/tags/v<major>.<minor>.<patch>` sur le fork.
- Les releases du fork portent les tags `shield-<n>`. Vérifié : `releases/tags/v1.1.1` renvoie 404 sur le fork.

`fetchReleaseNotes` (`…/ui/preferences/PreferencesViewModel.kt:172`) affiche donc toujours « Release not found ».

Recommandation : chercher la release par nom (le titre est la sortie de `git describe`), ou publier avec un tag dérivé de la version.

### F8 — Secret Quick Connect exposé dans les messages d'erreur — Faible

- `…/services/SeerrServerRepository.kt:216` : le secret est passé en paramètre d'URL.
- `…/services/SeerrServerRepository.kt:197` : l'exception contient `request.url`, donc le secret.
- `…/ui/setup/seerr/SwitchSeerrViewModel.kt:185-186` : ce message part dans logcat et dans l'état affiché à l'écran.

Le secret est de courte durée, mais il suffit à finaliser l'authentification. Recommandation : ne mettre que `request.url.encodedPath` dans le message.

Dans la même fonction : une seule erreur réseau pendant l'attente (`:217-223`) abandonne tout le parcours et génère un nouveau code avec l'URL candidate suivante. Tolérer quelques échecs consécutifs. `:195` utilise `Dispatchers.IO` alors que le reste du projet passe par `WholphinDispatchers.IO`.

### F9 — Session Quick Connect non renouvelable — Faible

- `…/services/UserSwitchListener.kt:114-130` : pour `QUICK_CONNECT`, le cookie stocké est rejoué tel quel.

Quand la session Seerr expire, l'appel échoue et l'intégration passe en erreur sans proposer de se reconnecter. Hypothèse : la durée de vie dépend de la configuration du serveur Seerr. Recommandation : sur un 401, proposer directement de relancer Quick Connect.

### F10 — Réglages affichés mais ignorés — Faible

- `…/services/PlayerFactory.kt:173-179` : tunneling et offload audio sont forcés à « désactivé » pour le lecteur vidéo.
- `…/preferences/ExperimentalPreference.kt:30` et `:86` : les deux réglages restent proposés à l'utilisateur.

Recommandation : masquer ces réglages dans le fork, ou en faire la valeur par défaut au lieu de les ignorer.

### F11 — Dette mineure introduite par le fork — Faible

- `…/ui/nav/NavDrawer.kt:468` : le bouton « Plus » est retiré mais l'état associé reste (`:132`, `:154`, `:304-308`), ainsi que `moreItems` dans `…/services/NavDrawerService.kt:229-257`. Les bibliothèques rangées là deviennent inaccessibles depuis le tiroir.
- `…/services/PlayerFactory.kt:324-344` : `AudioOutputState` est un objet global mutable, jamais réinitialisé entre deux lectures. Le panneau peut afficher brièvement l'état de la lecture précédente. Le rattacher à l'état de lecture (`CurrentPlayback`).
- `…/ui/playback/overlay/PlaybackInfoPanel.kt:135` et `:140`, `…/ui/setup/seerr/SwitchSeerrViewModel.kt:166` et `:189` : chaînes en dur en anglais alors que le fork livre une traduction française.
- `…/services/ArrCalendarService.kt:104` et `…/services/HomeDataService.kt:698` : accès à `WholphinApplication.instance` au lieu d'injecter le contexte, ce qui complique les tests.
- `app/build.gradle.kts:206` et `:235` : ABI limitée à `armeabi-v7a` et langues à `en`/`fr`. C'est voulu, mais les workflows amont `main.yml` et `release.yml` conservés dans le fork ne sont plus cohérents avec ce build.
- `…/services/PlayerFactory.kt:186-189` : le commentaire du builder et le message du commit `ef2fb98c` (« default load control ») ne décrivent pas la même chose que le code (`:216-220`, plafond de 64 Mo).

### Points positifs du fork

- Mémoire : cache d'images plafonné à 48 Mo (`…/ui/CoilConfig.kt:29`), palette calculée sur une image de 128 px (`…/services/BackdropService.kt:36`), préchargements trickplay limités au cache disque (`…/ui/playback/PlaybackViewModel.kt:1117`). Ces changements sont génériques et proposables à l'amont.
- Profil d'appareil tenant compte des capacités HDR de l'écran (`…/util/profile/DeviceProfileUtils.kt:80-107`), testé.
- Piste audio désactivée jusqu'au choix définitif pour éviter deux ouvertures HDMI successives (`…/ui/playback/PlaybackViewModel.kt:913-923`) : la logique de repli a été vérifiée, l'audio est bien réactivé dans les deux branches.

## 2. Sécurité

### S1 — Identifiants en clair et sauvegardés — Moyenne

- `…/data/model/JellyfinServer.kt:65-66` : `accessToken` et `pin` en clair dans Room.
- `…/data/MostRecentServer.kt:108` : jeton recopié dans des `SharedPreferences`.
- `…/data/model/SeerrServer.kt:58-59` : mot de passe Seerr et cookie de session en clair. Le mot de passe est conservé pour se reconnecter (`…/services/SeerrServerRepository.kt:158-166`).
- `…/ui/setup/SwitchUserContent.kt:388` : le PIN est comparé en clair.
- `app/src/main/AndroidManifest.xml:42` : `allowBackup="true"`, sans `dataExtractionRules` ni `fullBackupContent`.

Le stockage privé de l'application protège ces données sur un appareil non rooté, mais elles partent dans les sauvegardes et les transferts d'appareil. Recommandation, par ordre de rapport gain/effort :
1. Ajouter des règles d'extraction excluant la base `wholphin` et le fichier de préférences `*_server`.
2. Chiffrer les secrets avec une clé de l'Android Keystore.
3. Stocker le PIN sous forme de hachage salé.

### S2 — Trafic en clair et autorités utilisateur acceptés partout — Moyenne

- `app/src/main/AndroidManifest.xml:49` et `app/src/main/res/xml/network_security_config.xml:3-7` : HTTP autorisé pour tous les domaines, certificats utilisateur approuvés.

C'est un choix assumé pour des serveurs auto-hébergés en réseau local. Le risque est que jetons, mots de passe et clés API transitent en clair dès que l'URL saisie est en `http://`. Recommandation : afficher un avertissement à l'ajout d'un serveur en HTTP sur une adresse non privée.

### S3 — Mise à jour intégrée sans vérification d'intégrité — Moyenne

- `…/services/UpdateChecker.kt:198-280` : l'APK est téléchargé depuis l'URL lue dans la réponse JSON puis passé à l'installateur, sans contrôle d'empreinte.
- `…/preferences/AppPreference.kt:627-630` : l'URL de mise à jour est modifiable.

La seule barrière est la vérification de signature d'Android, neutralisée par F1 tant que la clé est publique. Recommandation : après correction de F1, publier l'empreinte SHA-256 dans la release (le workflow la calcule déjà, `.github/workflows/shield-build.yml:51`) et la vérifier avant l'installation. Restreindre aussi l'hôte de `downloadUrl` à GitHub.

### S4 — Chaîne CI — Faible

- `.github/actions/setup/action.yml:14` : `gradle/actions/setup-gradle@v4` est la seule action non épinglée par empreinte, alors que `renovate.json` impose `pinDigests`. Le job dispose de `contents: write`.
- `.github/workflows/sync-upstream.yml:20-23` : permissions `contents`, `actions` et `issues` en écriture pour tout le workflow. Les limiter par étape n'est pas possible, mais séparer rebase et publication en deux jobs réduirait la portée.
- `.github/workflows/sync-upstream.yml:77` : `git push origin 'refs/tags/v*' || true` masque tout échec.

### S5 — Divers — Faible

- `…/services/PlayerFactory.kt:308-319` et `…/services/MusicService.kt:128` : `TrustAllControllersCallback` accorde toutes les commandes du lecteur à tout contrôleur média (code amont, choix documenté). Limiter aux paquets connus serait plus prudent.
- `…/services/IntentService.kt:123-131` : l'activité exportée permet à toute application de changer d'utilisateur (non protégé) et de lancer une lecture. Comportement documenté dans `Intents.md`, à garder en tête.
- `…/WholphinApplication.kt:77` et `:87` : le rapport de plantage inclut logcat et les préférences partagées par défaut sans filtre. Le jeton n'y figure pas (il est dans un fichier nommé), mais toute future préférence sensible y partirait.
- `…/api/seerr/SeerrApiClient.kt:70` : `mutableMapOf` non synchronisé, accédé depuis les threads OkHttp. `…/services/SeerrApi.kt:15` : `var api` remplacé sans `@Volatile`.
- `…/services/SeerrServerRepository.kt:123` : la clé API Seerr est enregistrée avant d'être testée (`TODO` dans le code).

Dépendances : les versions de `gradle/libs.versions.toml` sont récentes (Media3 1.11.1, OkHttp 5.5.1, Room 2.8.5) et suivies par Renovate. Aucune dépendance obsolète relevée. Aucun audit de vulnérabilités automatisé n'a été lancé pour cette revue.

## 3. Performance

### P1 — Ligne « prochaines sorties » sans cache — Moyenne

- `…/services/ArrCalendarService.kt:78-107` : à chaque chargement de la ligne, une requête par instance, sur 60 jours (`:49`), avec `includeSeries=true` qui renvoie la série complète pour chaque épisode.
- `…/services/HomeDataService.kt:690-701` : appel direct, sans mémorisation.

Le calendrier change peu. Recommandation : cache mémoire de 15 à 30 minutes par utilisateur, invalidé à l'import. Le délai de lecture de 8 secondes (`:69`) retarde aussi la ligne quand une instance est injoignable.

### P2 — Requête supplémentaire à chaque lancement de lecture — Faible

- `…/services/PlaylistCreator.kt:429-434` et `…/ui/playback/PlaybackViewModel.kt:466` : les éléments de file sont allégés puis complétés par un `getItem` au moment de jouer.

Le gain mémoire est réel sur une longue file. Le coût est un aller-retour réseau avant chaque lecture d'épisode. Recommandation : précharger les détails de l'élément suivant pendant la lecture en cours, comme le fait déjà la logique « épisode suivant ».

### P3 — Requêtes en série pour les épisodes en plusieurs parties — Faible

- `…/services/PlaylistCreator.kt:331-337` : un `getAdditionalPart` séquentiel par épisode multi-parties (code amont). Paralléliser avec `async` borné par un sémaphore, comme `…/ui/main/HomeViewModel.kt:182`.

### P4 — Appels bloquants — Faible

- `…/services/MusicService.kt:90` : `runBlocking` dans l'initialisation paresseuse du lecteur pour lire les préférences. Selon l'appelant, cela bloque le thread principal sur une lecture DataStore.
- `…/util/ExceptionHandler.kt:34` : `runBlocking` pour afficher un toast depuis le gestionnaire d'exceptions. Si l'exception survient sur le thread principal, `withContext(Main)` à l'intérieur d'un `runBlocking` sur ce même thread peut se bloquer. Hypothèse dépendant du dispatcher (`Main` ou `Main.immediate`), non reproduite. Utiliser un `Handler` ou une portée applicative.

### P5 — Attente fixe avant lecture — Faible

- `…/services/ThemeSongPlayer.kt:163` et `:176` : jusqu'à 2 secondes d'attente si un thème venait de jouer. Constante empirique propre à la Shield. La rendre configurable ou la conditionner à une sortie en passthrough.

Points positifs : chargement de l'accueil parallélisé et borné (`…/ui/main/HomeViewModel.kt:182-261`), pagination avec cache de pages (`…/util/ApiRequestPager.kt:50`), profil de démarrage (`app/src/main/baseline-prof.txt`).

## 4. Design et architecture

### D1 — Stratégie de fork par rebase sur des fichiers très actifs — Moyenne

Le fork modifie en place des fichiers amont volumineux et souvent touchés : `PlaybackViewModel.kt` (1 843 lignes), `PlayerFactory.kt`, `PlaylistCreator.kt`, `AppPreference.kt` (1 561 lignes), `AppDatabase.kt`. Le conflit actuel (F2) en est la conséquence directe.

Recommandation :
- Isoler le code du fork dans des fichiers dédiés et réduire les modifications amont à des points d'extension d'une ligne (c'est déjà le cas pour `PlaybackErrorPolicy.kt`, `ArrCalendarService.kt`, `PlaybackInfoPanel.kt`).
- Proposer à l'amont les changements génériques : plafond du cache d'images, palette en petite taille, profil HDR selon l'écran, reprise sur erreur réseau. Chaque changement accepté est un conflit de moins.
- Rassembler les constantes propres à la Shield (64 Mo de tampon, 48 Mo de cache, 2 s d'attente) dans un objet de configuration unique.

### D2 — Classes trop larges — Moyenne

- `…/ui/playback/PlaybackViewModel.kt:142` : 1 843 lignes mêlant création du lecteur, choix des flux, sous-titres, trickplay, segments, file de lecture, rapports au serveur et gestion d'erreurs. Le fork y ajoute repli MPV et reprises réseau.
- `…/preferences/AppPreference.kt` : 1 561 lignes dans une seule interface scellée.
- `…/ui/components/CollectionFolderView.kt` : 1 207 lignes.

Recommandation : extraire de `PlaybackViewModel` un gestionnaire d'erreurs et de reprise (testable seul, ce qui couvrirait F5) et un sélecteur de backend.

### D3 — État global et singletons statiques — Faible

- `…/WholphinApplication.kt:121` : `instance` statique, référencée 27 fois au lieu de l'injection Hilt.
- `…/data/ServerRepository.kt:44-46` : DAO, client API et SDK exposés en `val` publics, ce qui encourage les appelants à contourner le dépôt.
- `…/services/hilt/AppModule.kt:98` : client OkHttp sans configuration (`TODO` : agent utilisateur, délais, journalisation). Les services en dérivent chacun leur variante avec des délais différents.

### D4 — Gestion d'erreurs — Faible

- Messages utilisateur en dur en anglais dans les ViewModels (`…/ui/setup/seerr/SwitchSeerrViewModel.kt:75`, `:103`, `:146`, `:208` ; `…/services/UpdateChecker.kt:106`, `:115`, `:276`).
- `…/services/UpdateChecker.kt:207` : `release.downloadUrl!!` plante si la release n'a pas d'asset correspondant à l'ABI.
- `…/services/hilt/DatabaseModule.kt:41-50` : pas de stratégie de repli si une migration échoue. C'est le bon choix pour ne pas perdre de données, mais il rend F3 bloquant.

### D5 — Tests — Faible

61 fichiers de tests unitaires et 4 instrumentés pour 489 fichiers source. Les utilitaires purs sont bien couverts. Ne sont pas couverts : `ServerRepository`, `UpdateChecker.installRelease`, `SeerrServerRepository` (dont Quick Connect), `ArrImportService.importFromSeerr`. Un `MockWebServer` suffirait pour Quick Connect et l'import.

## 5. Idées de fonctionnalités

1. **Calendrier complet des sorties.** La ligne d'accueil est limitée et sans « voir plus » (`showViewMore = false`, `…/services/HomeDataService.kt:694`). Une page dédiée, groupée par jour, réutiliserait `ArrCalendarService`.
2. **État de téléchargement sur les sorties.** Interroger la file Radarr/Sonarr (`/api/v3/queue`) pour afficher « en cours de téléchargement » ou « disponible » sur les cartes, et ouvrir directement l'élément Jellyfin quand il existe.
3. **Ajout manuel d'une instance Radarr/Sonarr.** L'import exige aujourd'hui un compte administrateur Seerr. Un formulaire URL + clé couvrirait les utilisateurs sans Seerr.
4. **Reconnexion Seerr guidée.** Sur session expirée (F9), afficher directement le code Quick Connect au lieu d'un état d'erreur.
5. **Mesures de lecture visibles.** `PlaybackMetricsListener` (`…/services/PlayerFactory.kt:351-439`) n'écrit que dans logcat. Afficher première image, remises en tampon, images perdues et débit dans le panneau d'informations ou la page de débogage, et les joindre au rapport envoyé au serveur.
6. **Notification des replis automatiques.** Indiquer brièvement à l'écran quand la lecture bascule sur MPV ou relance après une coupure réseau, pour que l'utilisateur comprenne un changement de comportement.
7. **Mise à jour vérifiée et canaux.** Vérification SHA-256 avant installation (S3), notes de version fonctionnelles (F7), et choix entre un canal stable et le canal nocturne.
8. **Profil matériel configurable.** Exposer tampon de lecture, taille du cache d'images et plafond AV1 logiciel sous forme de profil d'appareil (Shield 2 Go, appareil standard) plutôt que de constantes : le fork deviendrait utilisable ailleurs et plus facile à proposer à l'amont.
9. **Export et import des réglages.** Sauvegarde chiffrée des préférences et de la disposition de l'accueil vers le serveur Jellyfin, utile après le changement de clé de signature qu'impose F1.
