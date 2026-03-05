# Keep native bridge names stable for JNI symbol lookup.
-keep class com.scrollsnap.core.stitch.NativeFeatureStitcher {
    native <methods>;
}

# Keep Shizuku process bridge method that is invoked via reflection.
-keep class rikka.shizuku.Shizuku {
    public static *** newProcess(...);
}

# Keep entry components declared in manifest.
-keep class com.scrollsnap.MainActivity { *; }
-keep class com.scrollsnap.feature.control.OverlayControlService { *; }
-keep class com.scrollsnap.feature.control.ScrollSnapTileService { *; }

# Keep provider class used in manifest.
-keep class rikka.shizuku.ShizukuProvider { *; }
