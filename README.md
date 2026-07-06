# homeTV 📺

Uma aplicação de IPTV moderna, rápida e altamente otimizada, desenhada especificamente para **Android TV**, **Google TV** e **Fire TV Stick**. 

O **homeTV** foi desenvolvido com foco na usabilidade em comandos de televisão, zapping instantâneo e robustez na descodificação de canais, utilizando o motor de vídeo nativo **VideoLAN LibVLC**.

---

## ✨ Funcionalidades Principais

*   🚀 **Inicialização Instantânea com Splash Screen:** O arranque da aplicação apresenta um ecrã de carregamento elegante (`splashscreen.png`) com animação de *fade-out* (desvanecimento de 800ms) ao carregar. A lista M3U é descarregada e analisada em background, permitindo que a reprodução do último canal inicie por trás do ecrã de carregamento sem ecrãs pretos.
*   🎥 **Motor LibVLC Nativo (Sem Falhas de Codec):** Migrado do ExoPlayer para o **LibVLC 3.6.5**. Isto assegura suporte total a todos os formatos de streaming de IPTV (HLS, TS) e codecs de áudio/vídeo legados (como o codec de áudio `MPEG-L2` nas emissões de desporto, ex: Sport TV 5, que frequentemente ficam sem som noutros players).
*   ⚡ **Zapping Limpo e Sem Lags:** Ao mudar de canal, a app encerra e descarta completamente todas as threads, buffers de rede e decodificadores do canal anterior (`player.stop()` + `player.media = null`), eliminando o efeito de "imagem arrastada" ou congelamento.
*   🔄 **Botão "Atualizar" Canais:** Botão integrado na barra superior do menu lateral para forçar a transferência online e re-análise imediata da lista de canais, substituindo a cache local inteligente de 7 dias.
*   🚪 **Botão "Sair" Seguro:** Botão dedicado no menu superior para encerrar a aplicação de forma direta e limpa no comando da TV.
*   🎮 **Controlo Fluído via Comando (e Teclado):** 
    *   **Setas direcionais:** Navegação entre categorias e canais na grelha.
    *   **Enter (Return):** Reproduz o canal ou seleciona a categoria.
    *   **Back (Esc / M):** Abre e fecha o menu de canais. Previne a saída acidental da aplicação durante a emissão.
*   🌐 **Ordenação Inteligente de Canais:** Algoritmo otimizado no arranque que filtra e prioriza a grelha de canais nacionais (portugueses), posicionando-os no topo da lista por ordem numérica lógica (ex: RTP 1, RTP 2, SIC, TVI, canais de desporto, etc.), processando milhares de streams em milissegundos.
*   📅 **EPG (Guia de Programação) Integrado:** Integração com XMLTV para apresentar o programa atual ("A Dar") e o seguinte diretamente no banner informativo ao mudar de canal ou ao navegar na grelha de canais.

---

## 🛠️ Tecnologias Utilizadas

*   **Linguagem:** Kotlin
*   **Interface Gráfica:** Android Leanback / Custom Views otimizadas para ecrãs horizontais (16:9)
*   **Motor de Vídeo:** VideoLAN LibVLC Android SDK
*   **Ligação de Rede:** OkHttp3 para transferências assíncronas rápidas de listas volumosas
*   **Design de Ícone/Banner:** Formato oficial Android TV Leanback Banner (16:9 landscape)

---

## 📦 Estrutura do Código

*   [`MainActivity.kt`](file:///Users/lindote/Git/AgyIPTV/app/src/main/java/com/lindote/agyiptv/MainActivity.kt): Classe central que gere a interface, captura eventos do comando, controla o ciclo de vida do LibVLC, gere a Splash Screen e atualiza o painel de informação EPG.
*   [`M3uParser.kt`](file:///Users/lindote/Git/AgyIPTV/app/src/main/java/com/lindote/agyiptv/M3uParser.kt): Analisador assíncrono de alto desempenho que lê listas no formato `M3U_plus`, gere a cache local de 7 dias e extrai tags (`tvg-id`, `tvg-logo`, `group-title`).
*   [`XmltvManager.kt`](file:///Users/lindote/Git/AgyIPTV/app/src/main/java/com/lindote/agyiptv/XmltvManager.kt): Descarrega e faz o parse em background dos ficheiros XMLTV EPG de forma eficiente, mapeando os eventos da programação para o respetivo canal.

---

## 🚀 Como Compilar e Instalar

### Requisitos
*   Android Studio Jellyfish (ou superior)
*   Java Development Kit (JDK) 21
*   Android SDK (API 34)

### Compilar o APK
Para compilar o APK em modo de depuração (Debug), executa na pasta raiz do projeto:
```bash
./gradlew assembleDebug
```
O APK final será gerado em: `app/build/outputs/apk/debug/app-debug.apk`.

### Instalação Direta via ADB
Podes instalar o APK diretamente nas tuas Boxes de TV ligadas à mesma rede local através do ADB:
```bash
# Ligar à Box (substituir pelo IP da tua box)
adb connect 192.168.1.98:5555

# Instalar o APK compilado
adb -s 192.168.1.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔐 Configuração das Credenciais

No primeiro arranque da aplicação, prime o botão **Login** no menu superior para definir os dados da tua conta IPTV (Servidor, Utilizador e Palavra-passe). As credenciais são encriptadas e guardadas de forma persistente no dispositivo através do `SharedPreferences`.
