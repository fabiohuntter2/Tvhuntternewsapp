# TV Huntter DJ 🎙️📻

App Android para transmitir **ao vivo** na sua rádio pela **Porta DJ**, direto do celular, de qualquer lugar.

## Funções

- **Entrar no ar** pela porta DJ (SHOUTcast v1/v2 — MXCast, Centova, SonicPanel) ou Icecast/AzuraCast
- **Player** com playlist (arquivos ou pasta inteira), tocar/pausar, anterior/próxima, barra de posição, repetir e aleatório
- **Volume da música** e **volume do microfone** separados
- **Botão de microfone**: ao abrir, a música **abaixa automaticamente** (talkover/ducking, como nos softwares de automação de rádio) e volta sozinha ao fechar
- Ajuste do nível da música com o mic aberto e da velocidade de descida/volta
- **VU** de música, microfone e saída
- **Retorno no fone** (só a música, sem a sua voz — sem microfonia)
- Funciona com a **tela apagada** (serviço em primeiro plano, com controles na notificação)
- **Reconexão automática** se a internet cair
- Envia o **nome da música** para o servidor ("tocando agora")
- Codificação **MP3** 64/96/128/192 kbps feita no próprio celular

## Como instalar (teste)

1. No celular, abra **https://github.com/fabiohuntter2/tvhuntternewsapp/releases/latest**
2. Baixe o arquivo `TVHuntterDJ-v1.0.X.apk`
3. Abra o arquivo e permita **"Instalar apps desconhecidos"** quando o Android pedir
4. Abra o app e aceite as permissões de **microfone** e **notificações**

## Configuração para o painel MXCast

Na aba **Servidor**:

| Campo | Valor |
|---|---|
| Tipo | SHOUTcast / Porta DJ |
| Servidor | `stm1.mxcast.com.br` |
| Porta DJ | `35104` |
| Usuário DJ | o usuário de DJ criado no painel |
| Senha | a senha do DJ |

Toque em **SALVAR**, volte ao **Estúdio** e toque em **ENTRAR NO AR**.

> A senha fica salva **somente no celular** — ela não é gravada neste repositório (que é público).
> Se a porta informada recusar a conexão, o app tenta automaticamente a porta + 1 (padrão SHOUTcast DSP).

## Uso no ar

1. Monte a playlist na aba **Playlist**
2. **ENTRAR NO AR** → o status muda para **NO AR**
3. Toque ▶ para tocar a música
4. Toque no botão redondo **MIC** para falar — a música abaixa sozinha; toque de novo para fechar
5. Use **fone de ouvido** com microfone para a melhor qualidade

## Estrutura do projeto

- `core/` — motor de áudio em Kotlin puro: encoder MP3 (LAME/jump3r), mixer com ducking, protocolos SHOUTcast/Icecast, reconexão. Tem testes (`./gradlew :core:test`).
- `app/` — app Android (Jetpack Compose): decodificação das músicas, microfone, serviço em primeiro plano e telas.
- `.github/workflows/build-apk.yml` — a cada envio ao GitHub, compila o APK e publica em **Releases**.

## Assinatura

O APK de teste é assinado com `app/radiodj-teste.keystore` (chave de teste, para que as atualizações instalem por cima).
Para publicar na Play Store, gere uma chave própria e guarde-a fora do repositório.

## Licenças

Encoder MP3: jump3r (porta Java do LAME) — LGPL.
