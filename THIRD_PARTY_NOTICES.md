The optional Station Max LED integration was developed using the local protocol described by [Station Max Studio](https://github.com/SheEgAn27/station-max-studio). The animations and UI in Gemini Station are original.

MIT License

Copyright (c) 2026 SheEgAn

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The optional QR login flow is also based on [AlexxIT/YandexStation](https://github.com/AlexxIT/YandexStation), MIT License:

MIT License

Copyright (c) 2020 AlexxIT

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

QR encoding uses [ZXing Core](https://github.com/zxing/zxing), Apache License 2.0; the license is included in `licenses/zxing.txt`.

Custom IP-based DNS uses [dnsjava](https://github.com/dnsjava/dnsjava) 3.6.3 under the BSD 3-Clause license: `licenses/dnsjava.txt` (also bundled as `assets/licenses/dnsjava.txt`).

The experimental microWakeWord backend uses TensorFlow Lite (Apache 2.0) and the TensorFlow Lite Micro frontend as packaged by [pymicro-features 2.0.2](https://github.com/rhasspy/pymicro-features), Apache 2.0 (`licenses/microfrontend-apache-2.0.txt`, included in APK). The frontend includes KissFFT, BSD 3-Clause (`licenses/kissfft.txt`, included in APK). The bundled models were trained using synthesized Piper Dmitri and Irina voices; the newer model also uses speech generated using selected third-party TTS voices whose terms for redistributing a derived model have not been confirmed. The Irina model card lists the underlying RHVoice dataset licence as **unknown**, so rights for redistribution of the resulting trained model have not been established. Review before further distribution.


The app embeds [Google Sans](https://github.com/google/fonts/tree/main/ofl/googlesans) (font SHA-256 `d0a87d835a944b8b40d0e82a5651bb59ab97b936a2aeed5946eb57e7b2a3a90a`) under the SIL Open Font License 1.1. The license and trademark notices are in `licenses/google-sans-ofl.txt` and `licenses/google-sans-trademarks.md` and bundled in the APK. Google Sans is not the proprietary Product Sans font.

UI and media icons include [Material Icons](https://github.com/google/material-design-icons), Apache License 2.0 (`licenses/material-icons-apache-2.0.txt`, included in the APK). Google and Gemini marks are trademarks of their respective owners; this is an unofficial project and is not endorsed by Google.
