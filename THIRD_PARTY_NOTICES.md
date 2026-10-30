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

The offline wake-word detector uses [PocketSphinx Android](https://github.com/cmusphinx/pocketsphinx-android) (BSD-style licenses: `licenses/pocketsphinx-android.txt`, `licenses/pocketsphinx.txt`, `licenses/sphinxbase.txt`) and the CMUSphinx Russian acoustic model as distributed in [ru_pocketsphinx-cmu](https://github.com/synesthesiam/ru_pocketsphinx-cmu/tree/27ce4902d92ff64aa9f49aa2ca7aeca598e3e223), which declares GPL-3.0-or-later (`licenses/gpl-3.0.txt`). The model source and build-time downloads are identified in `tools/prepare_wakeword.sh`. Only acoustic model files are bundled; no user recordings are collected.

Custom IP-based DNS uses [dnsjava](https://github.com/dnsjava/dnsjava) 3.6.3 under the BSD 3-Clause license: `licenses/dnsjava.txt` (also bundled as `assets/licenses/dnsjava.txt`).
