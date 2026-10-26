package com.webypdf.app;

import android.app.Application;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

/** Initialises the PDF engine once for the whole app. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PDFBoxResourceLoader.init(getApplicationContext());
    }
}
