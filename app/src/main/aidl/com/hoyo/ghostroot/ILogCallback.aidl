package com.hoyo.ghostroot;

interface ILogCallback {
    void onLog(String line);
    void onDone(int exitCode);
}
