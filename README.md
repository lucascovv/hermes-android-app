# Hermes App — Aplicativo Android

Aplicativo Android nativo para a Plataforma Hermes de atendimento omnichannel.

## Funcionalidades

- **Conexão com qualquer servidor** — Insira a URL da sua plataforma Hermes na tela inicial
- **Todas as funções da plataforma** — Conversas, mensagens, áudio, contatos, kanban, etc.
- **Notificações nativas** — Alertas do Android com som e vibração
- **Envio/recebimento de áudio** — Suporte completo a gravação e reprodução
- **Upload de arquivos** — Fotos, documentos, mídia
- **Download de anexos** — Salva automaticamente na pasta Downloads
- **Pull-to-refresh** — Puxe para baixo para atualizar
- **Reconexão automática** — WebSocket se reconecta ao voltar do background
- **Compatibilidade** — Android 5.0 (Lollipop) até o mais recente

## Como compilar

### Opção 1: Android Studio (recomendado)

1. Instale o [Android Studio](https://developer.android.com/studio)
2. Abra a pasta `android-app/` como projeto
3. Aguarde o Gradle sincronizar
4. Conecte um dispositivo ou use emulador
5. Clique em **Run** (▶)

Para gerar o APK:
- **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- O APK estará em `app/build/outputs/apk/`

### Opção 2: Linha de comando

```bash
cd android-app

# Gerar wrapper (se necessário)
gradle wrapper --gradle-version 8.5

# Build debug
./gradlew assembleDebug

# Build release (minificado)
./gradlew assembleRelease
```

### Opção 3: GitHub Actions (automático)

O workflow `.github/workflows/build-android.yml` compila o APK automaticamente quando há push na pasta `android-app/`.

1. Vá em **Actions** no GitHub
2. Selecione **Build Android APK**
3. Os APKs ficam disponíveis como **Artifacts** do workflow

## Estrutura

```
android-app/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hermes/app/
│       │   ├── MainActivity.kt          → Tela principal (config URL + WebView)
│       │   ├── HermesApplication.kt     → Application class
│       │   ├── HermesWebViewClient.kt   → Navegação e erros
│       │   ├── HermesWebChromeClient.kt → Uploads, permissões, progresso
│       │   ├── WebAppInterface.kt       → Bridge JS ↔ Android nativo
│       │   ├── NotificationHelper.kt    → Notificações nativas Android
│       │   └── KeepAliveService.kt      → Serviço para manter conexão
│       └── res/                         → Layouts, ícones, cores, temas
├── build.gradle
└── gradle/
```

## Como funciona

1. **Primeira abertura** — Tela de configuração pede a URL do servidor (ex: `https://hermes.seudominio.com.br`)
2. **Login** — Após conectar, a tela de login da plataforma aparece automaticamente
3. **Uso normal** — O app funciona como a versão web com recursos nativos adicionais
4. **Notificações** — O app intercepta as notificações web e converte em notificações nativas do Android

## Requisitos Mínimos

- **Android**: 5.0 (API 21) ou superior
- **Armazenamento**: ~10 MB
- **Rede**: Wi-Fi ou dados móveis

## Notas Técnicas

- Usa WebView com JavaScript habilitado para rodar a plataforma web
- Bridge nativo intercepta `Notification API` e `ServiceWorker.showNotification` para criar notificações Android nativas
- Suporte a `getUserMedia` para gravação de áudio/vídeo
- Cookies persistentes para manter sessão
- WakeLock parcial de 2 minutos ao ir para background (mantém WebSocket vivo)
- SSL flexível para ambientes de desenvolvimento
