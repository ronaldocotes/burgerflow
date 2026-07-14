# Release do app do motoboy (MenuFlow)

App bare React Native 0.86 (`mobile/`), publicado fora da Play Store — o APK assinado é
distribuído por um endpoint próprio do backend (`/admin/app/releases`, ver PR #61).

## Keystore de release

A keystore de release **é estável**: se um update futuro for assinado com uma chave
diferente, o Android recusa a atualização (o usuário precisaria desinstalar e reinstalar).
Por isso ela é gerada **uma única vez** e reaproveitada em todas as versões.

- Arquivo: `menuflow-motoboy-release.jks` (alias `menuflow-motoboy`)
- Local seguro (fora do repo, fora do git): `C:\Users\sdcot\Cofre-local\menuflow-motoboy-keystore\`
- Senhas: guardadas em `~/.gradle/gradle.properties` (arquivo global do usuário, nunca
  entra no git) e também anotadas no cofre local (`COFRE-IMPORTAR.csv`, pasta "MenuFlow").
- **NUNCA** commitar o `.jks`, o `gradle.properties` local do projeto com as senhas, nem o
  `.apk` gerado. O `.gitignore` do repo já bloqueia `*.jks`, `*.keystore` (exceto o
  `debug.keystore` de desenvolvimento) e `*.apk`/`*.aab`.

Se a máquina de build for outra (ex.: outro dev, CI), configure as mesmas 4 variáveis em
`~/.gradle/gradle.properties` (ou como variáveis de ambiente, que servem de fallback):

```properties
MENUFLOW_UPLOAD_STORE_FILE=/caminho/para/menuflow-motoboy-release.jks
MENUFLOW_UPLOAD_STORE_PASSWORD=...
MENUFLOW_UPLOAD_KEY_ALIAS=menuflow-motoboy
MENUFLOW_UPLOAD_KEY_PASSWORD=...
```

`mobile/android/app/build.gradle` lê essas variáveis e monta `signingConfigs.release`
automaticamente. Se elas não estiverem presentes, o build de release cai de volta para a
`debug.keystore` (para não quebrar builds locais de quem não tem a keystore) — ou seja,
**sempre confira a assinatura do APK antes de publicar** (passo abaixo).

## Build manual (MVP — sem CI ainda)

```bash
cd mobile
npm install   # se ainda não tiver rodado
cd android
./gradlew assembleRelease
```

Rodar `gradlew` via bash/git-bash (não `cmd.exe`) neste ambiente — invocar via
`cmd //c gradlew.bat` não propaga o cwd corretamente para o subprocesso em algumas
configurações e falha com "não é reconhecido".

Saída: `mobile/android/app/build/outputs/apk/release/app-release.apk`.

## Verificar a assinatura

```bash
# achar o apksigner mais recente instalado no SDK
find "$ANDROID_HOME/build-tools" -name apksigner.bat | sort -r | head -1

# verificar (via cmd, apksigner.bat é script windows)
cmd //c "<caminho apksigner.bat>" verify --print-certs "<caminho app-release.apk>"
```

Confira que o SHA-1/SHA-256 impresso bate com o da keystore de release (não com o
`debug.keystore` do repo). Para conferir o fingerprint da própria keystore:

```bash
keytool -list -v -keystore <caminho .jks> -storepass <senha>
```

## Antes de bumpar versão

- `versionCode` (inteiro, incremental) e `versionName` (string, ex. `1.0.1`) ficam em
  `mobile/android/app/build.gradle`, dentro de `defaultConfig`.
- Toda release nova (mesmo hotfix) precisa incrementar o `versionCode` — é o que o backend
  usa para decidir se há atualização disponível.

## Publicar (depois que o backend do PR #61 estiver mergeado/deployado)

```bash
node scripts/publicar-versao.mjs \
  --apk mobile/android/app/build/outputs/apk/release/app-release.apk \
  --code <versionCode> \
  --name <versionName> \
  --notas "Descrição do que mudou nesta versão"
```

Confirmar publicação via `GET /admin/app/releases` (ou o endpoint de "latest" que o app
consulta para checar atualização).
