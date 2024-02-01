package com.android.commands.am;

import android.app.IActivityManager;
import android.app.IInstrumentationWatcher;
import android.content.ComponentName;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.ServiceManager;
import android.util.proto.ProtoOutputStream;
import android.view.IWindowManager;
import com.android.commands.am.InstrumentationData;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/* loaded from: classes.dex */
public class Instrument {
    public static final String DEFAULT_LOG_DIR = "instrument-logs";
    private static final int STATUS_TEST_FAILED_ASSERTION = -1;
    private static final int STATUS_TEST_FAILED_OTHER = -2;
    private static final int STATUS_TEST_PASSED = 0;
    private static final int STATUS_TEST_STARTED = 1;
    private static final String TAG = "am";
    public String componentNameArg;
    private final IActivityManager mAm;
    private final IPackageManager mPm;
    public String profileFile = null;
    public boolean wait = false;
    public boolean rawMode = false;
    boolean protoStd = false;
    boolean protoFile = false;
    String logPath = null;
    public boolean noWindowAnimation = false;
    public boolean disableHiddenApiChecks = false;
    public boolean disableIsolatedStorage = false;
    public String abi = null;
    public int userId = STATUS_TEST_FAILED_OTHER;
    public Bundle args = new Bundle();
    private final IWindowManager mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));

    /* loaded from: classes.dex */
    private interface StatusReporter {
        void onError(String str, boolean z);

        void onInstrumentationFinishedLocked(ComponentName componentName, int i, Bundle bundle);

        void onInstrumentationStatusLocked(ComponentName componentName, int i, Bundle bundle);
    }

    public Instrument(IActivityManager am, IPackageManager pm) {
        this.mAm = am;
        this.mPm = pm;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Collection<String> sorted(Collection<String> list) {
        ArrayList<String> copy = new ArrayList<>(list);
        Collections.sort(copy);
        return copy;
    }

    /* loaded from: classes.dex */
    private class TextStatusReporter implements StatusReporter {
        private boolean mRawMode;

        public TextStatusReporter(boolean rawMode) {
            this.mRawMode = rawMode;
        }

        @Override // com.android.commands.am.Instrument.StatusReporter
        public void onInstrumentationStatusLocked(ComponentName name, int resultCode, Bundle results) {
            String pretty = null;
            if (!this.mRawMode && results != null) {
                pretty = results.getString("stream");
            }
            if (pretty != null) {
                System.out.print(pretty);
                return;
            }
            if (results != null) {
                for (String key : Instrument.sorted(results.keySet())) {
                    PrintStream printStream = System.out;
                    printStream.println("INSTRUMENTATION_STATUS: " + key + "=" + results.get(key));
                }
            }
            PrintStream printStream2 = System.out;
            printStream2.println("INSTRUMENTATION_STATUS_CODE: " + resultCode);
        }

        @Override // com.android.commands.am.Instrument.StatusReporter
        public void onInstrumentationFinishedLocked(ComponentName name, int resultCode, Bundle results) {
            String pretty = null;
            if (!this.mRawMode && results != null) {
                pretty = results.getString("stream");
            }
            if (pretty != null) {
                System.out.println(pretty);
                return;
            }
            if (results != null) {
                for (String key : Instrument.sorted(results.keySet())) {
                    PrintStream printStream = System.out;
                    printStream.println("INSTRUMENTATION_RESULT: " + key + "=" + results.get(key));
                }
            }
            PrintStream printStream2 = System.out;
            printStream2.println("INSTRUMENTATION_CODE: " + resultCode);
        }

        @Override // com.android.commands.am.Instrument.StatusReporter
        public void onError(String errorText, boolean commandError) {
            if (this.mRawMode) {
                PrintStream printStream = System.out;
                printStream.println("onError: commandError=" + commandError + " message=" + errorText);
            }
            if (!commandError) {
                System.out.println(errorText);
            }
        }
    }

    /* loaded from: classes.dex */
    private class ProtoStatusReporter implements StatusReporter {
        private File mLog;
        private long mTestStartMs;

        ProtoStatusReporter() {
            if (Instrument.this.protoFile) {
                if (Instrument.this.logPath == null) {
                    File logDir = new File(Environment.getLegacyExternalStorageDirectory(), Instrument.DEFAULT_LOG_DIR);
                    if (!logDir.exists() && !logDir.mkdirs()) {
                        System.err.format("Unable to create log directory: %s\n", logDir.getAbsolutePath());
                        Instrument.this.protoFile = false;
                        return;
                    }
                    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-hhmmss-SSS", Locale.US);
                    String fileName = String.format("log-%s.instrumentation_data_proto", format.format(new Date()));
                    this.mLog = new File(logDir, fileName);
                } else {
                    this.mLog = new File(Environment.getLegacyExternalStorageDirectory(), Instrument.this.logPath);
                    File logDir2 = this.mLog.getParentFile();
                    if (!logDir2.exists() && !logDir2.mkdirs()) {
                        System.err.format("Unable to create log directory: %s\n", logDir2.getAbsolutePath());
                        Instrument.this.protoFile = false;
                        return;
                    }
                }
                if (this.mLog.exists()) {
                    this.mLog.delete();
                }
            }
        }

        @Override // com.android.commands.am.Instrument.StatusReporter
        public void onInstrumentationStatusLocked(ComponentName name, int resultCode, Bundle results) {
            ProtoOutputStream proto = new ProtoOutputStream();
            long testStatusToken = proto.start(2246267895809L);
            proto.write(1172526071811L, resultCode);
            writeBundle(proto, 1146756268036L, results);
            if (resultCode == 1) {
                this.mTestStartMs = System.currentTimeMillis();
            } else {
                long j = this.mTestStartMs;
                if (j > 0) {
                    proto.write(InstrumentationData.TestStatus.LOGCAT, Instrument.readLogcat(j));
                }
                this.mTestStartMs = 0L;
            }
            proto.end(testStatusToken);
            outputProto(proto);
        }

        @Override // com.android.commands.am.Instrument.StatusReporter
        public void onInstrumentationFinishedLocked(ComponentName name, int resultCode, Bundle results) {
            ProtoOutputStream proto = new ProtoOutputStream();
            long sessionStatusToken = proto.start(InstrumentationData.Session.SESSION_STATUS);
            proto.write(InstrumentationData.SessionStatus.STATUS_CODE, Instrument.STATUS_TEST_PASSED);
            proto.write(1172526071811L, resultCode);
            writeBundle(proto, 1146756268036L, results);
            proto.end(sessionStatusToken);
            outputProto(proto);
        }

        @Override // com.android.commands.am.Instrument.StatusReporter
        public void onError(String errorText, boolean commandError) {
            ProtoOutputStream proto = new ProtoOutputStream();
            long sessionStatusToken = proto.start(InstrumentationData.Session.SESSION_STATUS);
            proto.write(InstrumentationData.SessionStatus.STATUS_CODE, 1);
            proto.write(1138166333442L, errorText);
            proto.end(sessionStatusToken);
            outputProto(proto);
        }

        private void writeBundle(ProtoOutputStream proto, long fieldId, Bundle bundle) {
            long bundleToken = proto.start(fieldId);
            for (String key : Instrument.sorted(bundle.keySet())) {
                long entryToken = proto.startRepeatedObject(2246267895809L);
                proto.write(InstrumentationData.ResultsBundleEntry.KEY, key);
                Object val = bundle.get(key);
                if (val instanceof String) {
                    proto.write(1138166333442L, (String) val);
                } else if (val instanceof Byte) {
                    proto.write(1172526071811L, ((Byte) val).intValue());
                } else if (val instanceof Double) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_DOUBLE, ((Double) val).doubleValue());
                } else if (val instanceof Float) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_FLOAT, ((Float) val).floatValue());
                } else if (val instanceof Integer) {
                    proto.write(1172526071811L, ((Integer) val).intValue());
                } else if (val instanceof Long) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_LONG, ((Long) val).longValue());
                } else if (val instanceof Short) {
                    proto.write(1172526071811L, (int) ((Short) val).shortValue());
                } else if (val instanceof Bundle) {
                    writeBundle(proto, InstrumentationData.ResultsBundleEntry.VALUE_BUNDLE, (Bundle) val);
                } else if (val instanceof byte[]) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_BYTES, (byte[]) val);
                }
                proto.end(entryToken);
            }
            proto.end(bundleToken);
        }

        private void outputProto(ProtoOutputStream proto) {
            byte[] out = proto.getBytes();
            if (Instrument.this.protoStd) {
                try {
                    System.out.write(out);
                    System.out.flush();
                } catch (IOException ex) {
                    System.err.println("Error writing finished response: ");
                    ex.printStackTrace(System.err);
                }
            }
            if (Instrument.this.protoFile) {
                try {
                    OutputStream os = new FileOutputStream(this.mLog, true);
                    os.write(proto.getBytes());
                    os.flush();
                    os.close();
                } catch (IOException ex2) {
                    System.err.format("Cannot write to %s:\n", this.mLog.getAbsolutePath());
                    ex2.printStackTrace();
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class InstrumentationWatcher extends IInstrumentationWatcher.Stub {
        private boolean mFinished = false;
        private final StatusReporter mReporter;

        public InstrumentationWatcher(StatusReporter reporter) {
            this.mReporter = reporter;
        }

        public void instrumentationStatus(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                this.mReporter.onInstrumentationStatusLocked(name, resultCode, results);
                notifyAll();
            }
        }

        public void instrumentationFinished(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                this.mReporter.onInstrumentationFinishedLocked(name, resultCode, results);
                this.mFinished = true;
                notifyAll();
            }
        }

        public boolean waitForFinish() {
            synchronized (this) {
                while (!this.mFinished) {
                    try {
                        if (!Instrument.this.mAm.asBinder().pingBinder()) {
                            return false;
                        }
                        wait(1000L);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return true;
            }
        }
    }

    private ComponentName parseComponentName(String cnArg) throws Exception {
        if (cnArg.contains("/")) {
            ComponentName cn = ComponentName.unflattenFromString(cnArg);
            if (cn == null) {
                throw new IllegalArgumentException("Bad component name: " + cnArg);
            }
            return cn;
        }
        List<InstrumentationInfo> infos = this.mPm.queryInstrumentation((String) null, (int) STATUS_TEST_PASSED).getList();
        int numInfos = infos == null ? STATUS_TEST_PASSED : infos.size();
        ArrayList<ComponentName> cns = new ArrayList<>();
        for (int i = STATUS_TEST_PASSED; i < numInfos; i++) {
            InstrumentationInfo info = infos.get(i);
            ComponentName c = new ComponentName(info.packageName, info.name);
            if (cnArg.equals(info.packageName)) {
                cns.add(c);
            }
        }
        int i2 = cns.size();
        if (i2 == 0) {
            throw new IllegalArgumentException("No instrumentation found for: " + cnArg);
        } else if (cns.size() == 1) {
            return cns.get(STATUS_TEST_PASSED);
        } else {
            StringBuilder cnsStr = new StringBuilder();
            int numCns = cns.size();
            for (int i3 = STATUS_TEST_PASSED; i3 < numCns; i3++) {
                cnsStr.append(cns.get(i3).flattenToString());
                cnsStr.append(", ");
            }
            int i4 = cnsStr.length();
            cnsStr.setLength(i4 + STATUS_TEST_FAILED_OTHER);
            throw new IllegalArgumentException("Found multiple instrumentations: " + cnsStr.toString());
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:14:0x0025 A[Catch: all -> 0x00e8, Exception -> 0x00ea, TryCatch #0 {Exception -> 0x00ea, blocks: (B:3:0x0005, B:5:0x0009, B:8:0x000e, B:10:0x0012, B:14:0x0025, B:15:0x0031, B:17:0x0036, B:18:0x004e, B:20:0x0058, B:22:0x005f, B:25:0x006b, B:28:0x0071, B:29:0x0089, B:30:0x008a, B:32:0x008f, B:33:0x0091, B:35:0x0095, B:37:0x009c, B:40:0x00b2, B:42:0x00b8, B:49:0x00cd, B:50:0x00e7, B:11:0x001b), top: B:61:0x0005, outer: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:17:0x0036 A[Catch: all -> 0x00e8, Exception -> 0x00ea, TryCatch #0 {Exception -> 0x00ea, blocks: (B:3:0x0005, B:5:0x0009, B:8:0x000e, B:10:0x0012, B:14:0x0025, B:15:0x0031, B:17:0x0036, B:18:0x004e, B:20:0x0058, B:22:0x005f, B:25:0x006b, B:28:0x0071, B:29:0x0089, B:30:0x008a, B:32:0x008f, B:33:0x0091, B:35:0x0095, B:37:0x009c, B:40:0x00b2, B:42:0x00b8, B:49:0x00cd, B:50:0x00e7, B:11:0x001b), top: B:61:0x0005, outer: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:20:0x0058 A[Catch: all -> 0x00e8, Exception -> 0x00ea, TryCatch #0 {Exception -> 0x00ea, blocks: (B:3:0x0005, B:5:0x0009, B:8:0x000e, B:10:0x0012, B:14:0x0025, B:15:0x0031, B:17:0x0036, B:18:0x004e, B:20:0x0058, B:22:0x005f, B:25:0x006b, B:28:0x0071, B:29:0x0089, B:30:0x008a, B:32:0x008f, B:33:0x0091, B:35:0x0095, B:37:0x009c, B:40:0x00b2, B:42:0x00b8, B:49:0x00cd, B:50:0x00e7, B:11:0x001b), top: B:61:0x0005, outer: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:32:0x008f A[Catch: all -> 0x00e8, Exception -> 0x00ea, TryCatch #0 {Exception -> 0x00ea, blocks: (B:3:0x0005, B:5:0x0009, B:8:0x000e, B:10:0x0012, B:14:0x0025, B:15:0x0031, B:17:0x0036, B:18:0x004e, B:20:0x0058, B:22:0x005f, B:25:0x006b, B:28:0x0071, B:29:0x0089, B:30:0x008a, B:32:0x008f, B:33:0x0091, B:35:0x0095, B:37:0x009c, B:40:0x00b2, B:42:0x00b8, B:49:0x00cd, B:50:0x00e7, B:11:0x001b), top: B:61:0x0005, outer: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:35:0x0095 A[Catch: all -> 0x00e8, Exception -> 0x00ea, TryCatch #0 {Exception -> 0x00ea, blocks: (B:3:0x0005, B:5:0x0009, B:8:0x000e, B:10:0x0012, B:14:0x0025, B:15:0x0031, B:17:0x0036, B:18:0x004e, B:20:0x0058, B:22:0x005f, B:25:0x006b, B:28:0x0071, B:29:0x0089, B:30:0x008a, B:32:0x008f, B:33:0x0091, B:35:0x0095, B:37:0x009c, B:40:0x00b2, B:42:0x00b8, B:49:0x00cd, B:50:0x00e7, B:11:0x001b), top: B:61:0x0005, outer: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:36:0x009a  */
    /* JADX WARN: Removed duplicated region for block: B:39:0x00b0  */
    /* JADX WARN: Removed duplicated region for block: B:49:0x00cd A[Catch: all -> 0x00e8, Exception -> 0x00ea, TRY_ENTER, TryCatch #0 {Exception -> 0x00ea, blocks: (B:3:0x0005, B:5:0x0009, B:8:0x000e, B:10:0x0012, B:14:0x0025, B:15:0x0031, B:17:0x0036, B:18:0x004e, B:20:0x0058, B:22:0x005f, B:25:0x006b, B:28:0x0071, B:29:0x0089, B:30:0x008a, B:32:0x008f, B:33:0x0091, B:35:0x0095, B:37:0x009c, B:40:0x00b2, B:42:0x00b8, B:49:0x00cd, B:50:0x00e7, B:11:0x001b), top: B:61:0x0005, outer: #1 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void run() throws java.lang.Exception {
        /*
            Method dump skipped, instructions count: 254
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.commands.am.Instrument.run():void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String readLogcat(long startTimeMs) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            String timestamp = format.format(new Date(startTimeMs));
            Process process = new ProcessBuilder(new String[STATUS_TEST_PASSED]).command("logcat", "-d", "-v threadtime,uid", "-T", timestamp).start();
            process.getOutputStream().close();
            StringBuilder str = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(process.getInputStream());
            char[] buffer = new char[4096];
            while (true) {
                int amt = reader.read(buffer, STATUS_TEST_PASSED, buffer.length);
                if (amt < 0) {
                    try {
                        break;
                    } catch (InterruptedException e) {
                    }
                } else if (amt > 0) {
                    str.append(buffer, STATUS_TEST_PASSED, amt);
                }
            }
            process.waitFor();
            return str.toString();
        } catch (IOException ex) {
            return "Error reading logcat command:\n" + ex.toString();
        }
    }
}
