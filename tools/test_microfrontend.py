#!/usr/bin/env python3
"""Compile the vendored frontend on the host, compare it to training frontend."""
import ctypes
import subprocess
import tempfile
from pathlib import Path
import numpy as np
from pymicro_features import MicroFrontend

cpp = Path('app/src/main/cpp')
vendor = cpp / 'microfrontend'
lib = vendor / 'tensorflow/lite/experimental/microfrontend/lib'
k = vendor / 'kissfft'
names = ['kiss_fft_int16','fft','fft_util','filterbank','filterbank_util','frontend',
         'frontend_util','log_lut','log_scale','log_scale_util','noise_reduction',
         'noise_reduction_util','pcan_gain_control','pcan_gain_control_util','window','window_util']
with tempfile.TemporaryDirectory() as temp:
    so = str(Path(temp) / 'wake.so')
    cmd = ['g++', '-std=c++17', '-shared', '-fPIC', '-O2', '-DFIXED_POINT=16', '-I'+str(vendor),
           '-I'+str(k), str(cpp/'wake_frontend.cc')] + [str(lib/(n+'.cc')) for n in names]
    cmd += [str(k/'kiss_fft.cc'), str(k/'tools/kiss_fftr.cc'), '-o', so]
    subprocess.run(cmd, check=True)
    native = ctypes.CDLL(so)
    native.wake_frontend_create.restype = ctypes.c_void_p
    native.wake_frontend_process.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.POINTER(ctypes.c_float)]
    native.wake_frontend_destroy.argtypes = [ctypes.c_void_p]
    handle = native.wake_frontend_create()
    assert handle
    reference, rng, produced = MicroFrontend(), np.random.default_rng(19), 0
    try:
        for frame in range(1000):
            limit = 30000 if frame % 3 == 0 else 300
            pcm = rng.integers(-limit, limit, size=160, dtype=np.int16)
            actual = np.empty(40, dtype=np.float32)
            n = native.wake_frontend_process(handle, pcm.ctypes.data_as(ctypes.POINTER(ctypes.c_int16)), actual.ctypes.data_as(ctypes.POINTER(ctypes.c_float)))
            expected = reference.process_samples(pcm.tobytes())
            assert n == len(expected.features) and expected.samples_read == 160
            if n:
                np.testing.assert_array_equal(actual, np.array(expected.features, dtype=np.float32))
                produced += 1
    finally:
        native.wake_frontend_destroy(handle)
    assert produced == 998
    print('micro frontend matches 998 training feature frames')
