# HY Home: launcher leve para o projetor HY320

Tela inicial feita para controle remoto. Java puro, sem bibliotecas, APK com menos de 1 MB.

- Relógio, data, saudação e status da rede
- Fileira de favoritos (segure OK num app pra fixar, mover ou remover)
- Atalhos: Wi-Fi, Bluetooth, Som, Configurações
- Grade com todos os apps instalados (inclusive apps de TV)
- Ícones carregam em segundo plano e só recarregam quando um app é instalado ou removido

## 1. Subir pro GitHub (só navegador)

1. Acesse https://github.com/new
   - Nome do repositório: `hy320-launcher`
   - Deixe **Public** (assim o projetor consegue baixar o APK direto)
   - Clique em **Create repository**
2. Na página do repositório, clique em **uploading an existing file**.
3. Abra a pasta `HY320-Launcher` no Explorer, selecione **tudo que está dentro dela**
   (inclusive a pasta `.github`) e arraste pra página.
4. Clique em **Commit changes**.

## 2. Esperar compilar

- Aba **Actions**: o "Compilar APK" leva uns 3–5 minutos. Bolinha verde = pronto.
- O APK aparece em **Releases**, na lateral direita do repositório.

## 3. Instalar no projetor

No navegador do projetor, abra:

```
https://github.com/anndjr/hy320-launcher/releases/latest/download/HY320-Launcher.apk
```

(ou baixe no PC e passe por pendrive)

- Se pedir, permita "instalar apps de fontes desconhecidas" pro navegador/gerenciador de arquivos.
- Depois de instalar, aperte **HOME** no controle e escolha **HY Home** → **Sempre**.
- Se não aparecer a escolha: Configurações → Apps → Apps padrão → App de início → HY Home.

## Atualizar

Mudou o código? Faça upload dos arquivos alterados de novo no GitHub. Ele compila sozinho e
o link acima passa a baixar a versão nova, que instala por cima da antiga (a chave em
`keystore/` mantém a assinatura igual — não apague essa pasta).
