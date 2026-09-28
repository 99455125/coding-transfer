package dev.codingtransfer.plugin;

/** Resolves real libSystem exports only; does not access the GUI or user clipboard. */
public final class MacNativeSymbolSmokeTest {
    private MacNativeSymbolSmokeTest() {}
    public static void main(String[] args){
        if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("mac")){
            System.out.println("SKIP macOS native symbol smoke test (not macOS)");return;
        }
        MacMainQueue.verifyNativeSymbols();
        System.out.println("PASS macOS _dispatch_main_q and dispatch_async_f symbol resolution");
    }
}
