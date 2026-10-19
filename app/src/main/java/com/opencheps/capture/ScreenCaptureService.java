package com.opencheps.capture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.opencheps.R;
import com.opencheps.overlay.FloatingPanel;
import com.opencheps.overlay.OverlayService;
import com.opencheps.util.Prefs;
import com.opencheps.util.Side;
import com.opencheps.vision.BoardDetector;
import com.opencheps.vision.MoveAnalyzer;
import com.opencheps.vision.PieceClassifier;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Diagnose der Zugerkennung; keine Engine und keine automatische Zugausführung.
 *
 * Voraussetzungen:
 * - Java 8
 * - minSdk >= 21
 * - compileSdk >= 34
 * - Overlay-Berechtigung
 *
 * Manifest-Berechtigungen:
 * android.permission.FOREGROUND_SERVICE
 * android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
 * android.permission.SYSTEM_ALERT_WINDOW
 *
 * Service-Eintrag:
 * android:foregroundServiceType="mediaProjection"
 * android:exported="false"
 *
 * Vor dem Start aus einer sichtbaren Activity die Bildschirmfreigabe einholen.
 * Anschließend ContextCompat.startForegroundService verwenden.
 * Jede neue Aufnahmesitzung benötigt eine neue Freigabe.
 *
 * Aufnahme, Klassifikator und Brettzustand gehören ausschließlich dem Worker.
 * Panel und Overlay werden ausschließlich auf dem Hauptthread angesprochen.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "OC/DIAG";
    private static final String CHANNEL_ID = "opencheps_capture";
    private static final int NOTIFICATION_ID = 7;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_USER_SIDE = "user_side";
    public static final String ACTION_STOP = "com.opencheps.STOP";

    private static final long SAMPLE_PERIOD_MS = 400;
    private static final long PANEL_RETRY_MS = 500;
    private static final long SETTLE_MS = 300;
    private static final long MAX_CONFIRMATION_GAP_MS = 1600;

    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final int LOST_BOARD_THRESHOLD = 6;
    private static final int MAX_SAMPLE_ERRORS = 3;
    private static final int MAX_CAPTURE_EDGE = 800;
    private static final int MAX_IMAGES = 3;

    private static final int COLOR_READY = 0x6600C853;
    private static final int COLOR_WAITING = 0x66FFB300;
    private static final int COLOR_ERROR = 0x66D32F2F;

    /*
     * Ausgangswert für die Annahme eines Zuges.
     * Mit echten Ergebnissen von MoveAnalyzer abstimmen.
     * Der Score ist keine kalibrierte Fehlerwahrscheinlichkeit.
     */
    private static final double MIN_MOVE_CONFIDENCE = 0.80;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopping;
    private volatile UiState latestUi;

    /*
     * Hauptthread.
     * Die Handler-Referenzen bleiben während des Stopps erhalten.
     */
    private boolean started;
    private boolean overlayStarted;

    private HandlerThread workerThread;
    private Handler worker;

    private FloatingPanel boundPanel;
    private boolean updatingPanelSide;

    private Side renderedSide;
    private int renderedScreenW;
    private int renderedScreenH;

    /*
     * Ausschließlich Worker, einschließlich Ressourcenfreigabe.
     */
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private PieceClassifier classifier;

    private boolean resourcesReleased;
    private boolean paused;
    private boolean contentVisible = true;
    private boolean receivedContentSize;

    private int captureW;
    private int captureH;
    private int sourceW;
    private int sourceH;
    private int captureDensity;

    private int screenW;
    private int screenH;
    private int density;
    private int rotation = -1;

    private int[] rgbaPixels;

    private long lastImageTimestamp;
    private long settleUntil;

    private int consecutiveErrors;

    private Side userSide = Side.WHITE;
    private Side boardPerspective = Side.WHITE;
    private Side expectedSide = Side.WHITE;

    private char[][] confirmedGrid;
    private BoardDetector.Board lockedBoard;

    private int lostBoardCount;

    private String lastMoveText = "";

    private String confirmedEvalText =
            "INTERN: Weiß am Zug\nWarte auf ersten Zug";

    private String statusText = "DIAGNOSE startet…";

    private String evalText =
            "Keine Zugvorschläge\nStartstellung zeigen";

    private Rect overlayBounds;
    private int frameColor = COLOR_WAITING;

    private final StableGrid candidate = new StableGrid();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(
            @Nullable Intent intent,
            int flags,
            int startId
    ) {
        if (stopping) {
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop(null);
            return START_NOT_STICKY;
        }

        /*
         * Ein weiterer Start darf weder den Token erneut verbrauchen
         * noch einen zweiten Worker oder eine zweite Schleife erzeugen.
         */
        if (started) {
            Log.i(TAG, "Already running; duplicate start ignored");
            return START_NOT_STICKY;
        }

        if (intent == null) {
            requestStop(null);
            return START_NOT_STICKY;
        }

        try {
            int resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
            );

            Intent data = readProjectionData(intent);

            if (resultCode != Activity.RESULT_OK || data == null) {
                requestStop("Bitte die Bildschirmfreigabe erneut bestätigen.");
                return START_NOT_STICKY;
            }

            if (Build.VERSION.SDK_INT >= 23
                    && !Settings.canDrawOverlays(this)) {
                requestStop("Bitte die Anzeige über anderen Apps erlauben.");
                return START_NOT_STICKY;
            }

            String side = intent.getStringExtra(EXTRA_USER_SIDE);

            if (side == null) {
                side = Prefs.userSide(this);
            }

            final Side initialSide =
                    "b".equals(side) ? Side.BLACK : Side.WHITE;

            startInForeground();

            started = true;

            workerThread = new HandlerThread("oc-diagnostic");
            workerThread.start();

            worker = new Handler(workerThread.getLooper());

            startService(new Intent(this, OverlayService.class));
            overlayStarted = true;

            main.post(panelWatcher);

            if (!worker.post(
                    () -> initializeCapture(resultCode, data, initialSide)
            )) {
                requestStop("Die Aufnahme konnte nicht gestartet werden.");
            }

        } catch (RuntimeException error) {
            Log.e(TAG, "Capture start failed", error);

            requestStop(
                    "Aufnahme nicht gestartet. "
                            + "Freigabe und Berechtigungen prüfen."
            );
        }

        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent readProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent.class
            );
        }

        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void initializeCapture(
            int resultCode,
            Intent data,
            Side initialSide
    ) {
        if (stopping) {
            return;
        }

        try {
            userSide = initialSide;
            classifier = new PieceClassifier();

            readScreenMetrics();

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            MEDIA_PROJECTION_SERVICE
                    );

            if (manager == null) {
                throw new IllegalStateException(
                        "MediaProjectionManager unavailable"
                );
            }

            projection = manager.getMediaProjection(resultCode, data);

            if (projection == null) {
                throw new IllegalStateException(
                        "MediaProjection unavailable"
                );
            }

            projection.registerCallback(projectionCallback, worker);

            resizeCapture(screenW, screenH);
            publishUi();

            if (!stopping) {
                worker.postDelayed(sampleLoop, SETTLE_MS);
            }

        } catch (RuntimeException error) {
            Log.e(TAG, "Capture initialization failed", error);

            requestStop(
                    "Aufnahme fehlgeschlagen. "
                            + "Bitte eine neue Bildschirmfreigabe starten."
            );
        }
    }

    private final MediaProjection.Callback projectionCallback =
            new MediaProjection.Callback() {

                @Override
                public void onStop() {
                    requestStop(
                            "Die Bildschirmfreigabe wurde beendet."
                    );
                }

                @Override
                public void onCapturedContentResize(
                        int width,
                        int height
                ) {
                    if (stopping || width <= 0 || height <= 0) {
                        return;
                    }

                    try {
                        boolean displayChanged = readScreenMetrics();

                        receivedContentSize = true;

                        boolean captureChanged =
                                resizeCapture(width, height);

                        if (displayChanged || captureChanged) {
                            resetCalibration(
                                    "Aufnahmegröße geändert – "
                                            + "Startstellung zeigen"
                            );
                        }

                        publishUi();

                    } catch (RuntimeException error) {
                        Log.e(TAG, "Capture resize failed", error);

                        requestStop(
                                "Die Aufnahmegröße konnte nicht "
                                        + "angepasst werden."
                        );
                    }
                }

                @Override
                public void onCapturedContentVisibilityChanged(
                        boolean isVisible
                ) {
                    if (stopping || contentVisible == isVisible) {
                        return;
                    }

                    contentVisible = isVisible;

                    candidate.reset();
                    overlayBounds = null;

                    settleUntil =
                            SystemClock.uptimeMillis() + SETTLE_MS;

                    statusText = isVisible
                            ? "Warte auf stabiles Brett"
                            : "Freigegebene App nicht sichtbar";

                    publishUi();
                }
            };

    private final Runnable sampleLoop = new Runnable() {

        @Override
        public void run() {
            if (stopping) {
                return;
            }

            long begin = SystemClock.uptimeMillis();

            try {
                if (readScreenMetrics()) {
                    if (Build.VERSION.SDK_INT < 34
                            || !receivedContentSize) {
                        resizeCapture(screenW, screenH);

                    } else if (sourceW > 0
                            && captureDensity != density) {
                        resizeCapture(sourceW, sourceH);
                    }

                    resetCalibration(
                            "Anzeige geändert – Startstellung zeigen"
                    );
                }

                if (paused
                        || !contentVisible
                        || begin < settleUntil) {
                    drainReader();
                } else {
                    sampleOnce(begin);
                }

                consecutiveErrors = 0;

            } catch (Exception error) {
                candidate.reset();
                overlayBounds = null;

                consecutiveErrors++;

                statusText = "Diagnose-Fehler";
                evalText =
                        "Bei wiederholten Fehlern Aufnahme neu starten";

                Log.e(
                        TAG,
                        "Frame processing failed ("
                                + consecutiveErrors + ")",
                        error
                );

                if (consecutiveErrors >= MAX_SAMPLE_ERRORS) {
                    requestStop(
                            "Die Auswertung ist wiederholt fehlgeschlagen. "
                                    + "Aufnahme neu starten."
                    );
                }

            } finally {
                publishUi();
            }

            if (!stopping) {
                long elapsed = SystemClock.uptimeMillis() - begin;

                worker.postDelayed(
                        this,
                        Math.max(20L, SAMPLE_PERIOD_MS - elapsed)
                );
            }
        }
    };

    private void sampleOnce(long now) throws Exception {
        candidate.expire(now);

        if (reader == null || classifier == null) {
            return;
        }

        Bitmap bitmap = null;

        try {
            /*
             * Das Image wird auch geschlossen,
             * wenn die Konvertierung scheitert.
             */
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) {
                    return;
                }

                long timestamp = image.getTimestamp();

                if (timestamp > 0
                        && timestamp == lastImageTimestamp) {
                    return;
                }

                lastImageTimestamp = timestamp;

                if (image.getWidth() != captureW
                        || image.getHeight() != captureH) {
                    candidate.reset();
                    return;
                }

                bitmap = imageToBitmap(image);
            }

            if (stopping) {
                return;
            }

            BoardDetector.Board detected =
                    BoardDetector.locate(bitmap);

            if (!validBoard(
                    detected,
                    bitmap.getWidth(),
                    bitmap.getHeight()
            )) {
                candidate.reset();
                overlayBounds = null;

                lostBoardCount = Math.min(
                        LOST_BOARD_THRESHOLD,
                        lostBoardCount + 1
                );

                if (lostBoardCount >= LOST_BOARD_THRESHOLD) {
                    statusText = "Brett nicht erkannt";
                }

                return;
            }

            lostBoardCount = 0;

            BoardDetector.Board board = detected;

            if (classifier.isTrained() && lockedBoard != null) {
                if (boardMovedTooFar(lockedBoard, detected)) {
                    candidate.reset();
                    overlayBounds = null;

                    statusText = "Brett verschoben";
                    evalText =
                            "Neu kalibrieren und Startstellung zeigen";

                    return;
                }

                board = lockedBoard;
            }

            overlayBounds = canMapToScreen()
                    ? boardBoundsOnScreen(board)
                    : null;

            if (!classifier.isTrained()) {
                calibrate(bitmap, board);
                return;
            }

            char[][] observed =
                    classifier.classifyBoard(bitmap, board);

            if (!isGridValid(observed)) {
                candidate.reset();

                statusText = "Figuren nicht eindeutig erkannt";
                frameColor = COLOR_WAITING;

                return;
            }

            if (!isGridValid(confirmedGrid)) {
                resetCalibration(
                        "Stellung zurückgesetzt – Startstellung zeigen"
                );
                return;
            }

            if (gridsEqual(observed, confirmedGrid)) {
                candidate.reset();

                frameColor = COLOR_READY;

                statusText = lastMoveText.isEmpty()
                        ? "Warte auf Zug"
                        : "ERKANNT: " + lastMoveText;

                evalText = confirmedEvalText;

                /*
                 * Kein fortlaufendes Nachtrainieren
                 * auf jedem Bildschirmbild.
                 */
                return;
            }

            if (!candidate.observe(observed, now)) {
                statusText = "Bildänderung erkannt – bestätige…";
                frameColor = COLOR_WAITING;
                return;
            }

            /*
             * Der Analyzer erhält keine Referenz
             * auf die bestätigte Stellung.
             */
            char[][] proposal = copyGrid(observed);

            MoveAnalyzer.Result move = MoveAnalyzer.analyze(
                    copyGrid(confirmedGrid),
                    proposal
            );

            if (!validMoveResult(move, confirmedGrid, proposal)) {
                statusText = "NICHT SICHER";
                frameColor = COLOR_WAITING;

                evalText =
                        "ÄNDERUNGEN: "
                                + countChanges(confirmedGrid, observed)
                                + "\nERWARTET: "
                                + sideGerman(expectedSide)
                                + "\nLetzte bestätigte Stellung bleibt erhalten";

                return;
            }

            if (stopping) {
                return;
            }

            boolean recoveredSync = move.mover != expectedSide;

            String from = squareNameFor(
                    move.fromRow,
                    move.fromFile,
                    boardPerspective
            );

            String to = squareNameFor(
                    move.toRow,
                    move.toFile,
                    boardPerspective
            );

            int confidence =
                    (int) Math.round(move.confidence * 100.0);

            /*
             * Reparierte Folgestellungen nicht als Bild-Trainingsdaten
             * verwenden. Nur eine exakt passende, mehrfach beobachtete
             * Stellung nachtrainieren.
             */
            if (gridsEqual(proposal, observed)) {
                try {
                    classifier.commitConfirmed(
                            copyGrid(proposal),
                            bitmap,
                            board
                    );

                } catch (Exception error) {
                    resetCalibration(
                            "Kalibrierung fehlgeschlagen – "
                                    + "Startstellung zeigen"
                    );

                    throw error;
                }
            }

            confirmedGrid = copyGrid(proposal);
            expectedSide = opposite(move.mover);

            candidate.reset();
            frameColor = COLOR_READY;

            lastMoveText =
                    sideGerman(move.mover)
                            + " "
                            + from
                            + "→"
                            + to;

            statusText = recoveredSync
                    ? "SYNC KORRIGIERT: " + lastMoveText
                    : "ERKANNT: " + lastMoveText;

            evalText =
                    "ERKANNT: "
                            + lastMoveText
                            + "\nDANACH: "
                            + sideGerman(expectedSide)
                            + " am Zug"
                            + "\nÄNDERUNGEN: "
                            + move.changeCount
                            + " | SCORE: "
                            + confidence
                            + "%"
                            + "\nART: "
                            + kindGerman(move.kind);

            confirmedEvalText = evalText;

            Log.i(
                    TAG,
                    "MOVE "
                            + lastMoveText
                            + " kind="
                            + move.kind
                            + " confidence="
                            + confidence
                            + "% syncRecovered="
                            + recoveredSync
            );

        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private void calibrate(
            Bitmap bitmap,
            BoardDetector.Board board
    ) throws Exception {
        candidate.reset();
        frameColor = COLOR_WAITING;

        if (!classifier.tryBootstrap(bitmap, board)) {
            statusText = "Startstellung zeigen";
            return;
        }

        Side perspective = classifier.bootstrapSide();

        if (perspective != userSide) {
            classifier.reset();

            statusText = "Brett/Farbe falsch";
            evalText = "Andere Farbe wählen oder Brett drehen";
            frameColor = COLOR_ERROR;

            return;
        }

        /*
         * tryBootstrap übernimmt die Startstellungsprüfung.
         * Dessen Erkennungsqualität muss in PieceClassifier
         * anhand echter Bildschirmbilder geprüft werden.
         */
        boardPerspective = perspective;
        lockedBoard = board;

        confirmedGrid = startingGridFor(perspective);
        expectedSide = Side.WHITE;

        lastMoveText = "";
        frameColor = COLOR_READY;

        statusText = "DIAGNOSE bereit";

        confirmedEvalText =
                "INTERN: Weiß am Zug\nWarte auf ersten Zug";

        evalText = confirmedEvalText;

        Log.i(TAG, "CALIBRATED side=" + perspective);
    }

    private void resetCalibration(String message) {
        confirmedGrid = null;

        candidate.reset();

        lockedBoard = null;
        expectedSide = Side.WHITE;
        boardPerspective = userSide;

        lostBoardCount = 0;
        lastMoveText = "";

        overlayBounds = null;
        frameColor = COLOR_WAITING;

        statusText = message;
        evalText = "Startstellung zeigen";

        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;

        if (classifier != null) {
            classifier.reset();
        }
    }

    private final FloatingPanel.Listener panelListener =
            new FloatingPanel.Listener() {

                @Override
                public void onPauseToggle(boolean isPaused) {
                    postCommand(() -> {
                        paused = isPaused;

                        candidate.reset();
                        overlayBounds = null;

                        drainReader();

                        settleUntil =
                                SystemClock.uptimeMillis() + SETTLE_MS;

                        statusText = paused
                                ? "Diagnose pausiert"
                                : "Diagnose läuft";
                    });
                }

                @Override
                public void onStop() {
                    requestStop(null);
                }

                @Override
                public void onRecalibrate() {
                    postCommand(() -> {
                        resetCalibration("Neu kalibrieren");
                        drainReader();
                    });
                }

                @Override
                public void onSideChange(Side newSide) {
                    if (updatingPanelSide
                            || (newSide != Side.WHITE
                            && newSide != Side.BLACK)) {
                        return;
                    }

                    postCommand(() -> {
                        if (userSide == newSide) {
                            return;
                        }

                        userSide = newSide;

                        Prefs.setUserSide(
                                getApplicationContext(),
                                newSide == Side.BLACK ? "b" : "w"
                        );

                        resetCalibration("Farbe geändert");
                        drainReader();
                    });
                }
            };

    private void postCommand(Runnable command) {
        Handler target = worker;

        if (stopping || target == null) {
            return;
        }

        target.post(() -> {
            if (stopping) {
                return;
            }

            try {
                command.run();
                publishUi();

            } catch (RuntimeException error) {
                Log.e(TAG, "Panel command failed", error);

                requestStop(
                        "Die Diagnose konnte nicht aktualisiert werden. "
                                + "Bitte neu starten."
                );
            }
        });
    }

    private static final FloatingPanel.Listener DETACHED_LISTENER =
            new FloatingPanel.Listener() {

                @Override
                public void onPauseToggle(boolean value) {
                }

                @Override
                public void onStop() {
                }

                @Override
                public void onRecalibrate() {
                }

                @Override
                public void onSideChange(Side side) {
                }
            };

    private final Runnable renderUi = this::renderLatestUi;

    private final Runnable panelWatcher = new Runnable() {

        @Override
        public void run() {
            if (stopping) {
                return;
            }

            renderLatestUi();

            if (!stopping) {
                main.postDelayed(this, PANEL_RETRY_MS);
            }
        }
    };

    private void publishUi() {
        if (stopping) {
            return;
        }

        String status = paused
                ? "Diagnose pausiert"
                : statusText;

        String evaluation = evalText;

        if (receivedContentSize && !canMapToScreen()) {
            evaluation +=
                    "\nApp-Freigabe: keine Bildschirmmarkierung";
        }

        latestUi = new UiState(
                status,
                evaluation,
                userSide,
                screenW,
                screenH,
                paused || !contentVisible ? null : overlayBounds,
                frameColor
        );

        /*
         * Bei einem ausgelasteten Hauptthread keine
         * unbegrenzte Warteschlange mit Anzeigeupdates erzeugen.
         */
        main.removeCallbacks(renderUi);
        main.post(renderUi);
    }

    private void renderLatestUi() {
        if (stopping) {
            return;
        }

        try {
            FloatingPanel panel = OverlayService.panel();

            if (panel != boundPanel) {
                if (boundPanel != null) {
                    boundPanel.setListener(DETACHED_LISTENER);
                }

                boundPanel = panel;

                renderedSide = null;
                renderedScreenW = 0;
                renderedScreenH = 0;

                if (panel != null) {
                    panel.setListener(panelListener);
                }
            }

            UiState ui = latestUi;

            if (ui == null) {
                return;
            }

            if (panel != null) {
                if (ui.screenW > 0
                        && ui.screenH > 0
                        && (ui.screenW != renderedScreenW
                        || ui.screenH != renderedScreenH)) {

                    panel.placeBottomLeft(
                            ui.screenW,
                            ui.screenH
                    );

                    renderedScreenW = ui.screenW;
                    renderedScreenH = ui.screenH;
                }

                if (renderedSide != ui.side) {
                    updatingPanelSide = true;

                    try {
                        panel.setUserSide(ui.side);
                        renderedSide = ui.side;

                    } finally {
                        updatingPanelSide = false;
                    }
                }

                panel.setStatus(ui.status);
                panel.setEval(ui.evaluation);
            }

            if (ui.bounds == null) {
                OverlayService.clearAll();

            } else {
                OverlayService.setBoardBounds(
                        new Rect(ui.bounds)
                );

                OverlayService.setBoardFrameColor(ui.color);
                OverlayService.clearArrowsOnly();
            }

        } catch (RuntimeException error) {
            Log.e(TAG, "Overlay update failed", error);

            requestStop(
                    "Das Diagnosefenster konnte nicht angezeigt werden."
            );
        }
    }

    private static final class UiState {

        final String status;
        final String evaluation;

        final Side side;

        final int screenW;
        final int screenH;

        final Rect bounds;
        final int color;

        UiState(
                String status,
                String evaluation,
                Side side,
                int screenW,
                int screenH,
                Rect bounds,
                int color
        ) {
            this.status = status;
            this.evaluation = evaluation;
            this.side = side;
            this.screenW = screenW;
            this.screenH = screenH;

            this.bounds =
                    bounds == null ? null : new Rect(bounds);

            this.color = color;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean readScreenMetrics() {
        WindowManager manager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        if (manager == null) {
            throw new IllegalStateException(
                    "WindowManager unavailable"
            );
        }

        int width;
        int height;

        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds =
                    manager.getMaximumWindowMetrics().getBounds();

            width = bounds.width();
            height = bounds.height();

        } else {
            DisplayMetrics metrics = new DisplayMetrics();

            manager.getDefaultDisplay().getRealMetrics(metrics);

            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }

        int newDensity =
                getResources().getConfiguration().densityDpi;

        int newRotation =
                manager.getDefaultDisplay().getRotation();

        if (width <= 0 || height <= 0 || newDensity <= 0) {
            throw new IllegalStateException(
                    "Invalid display metrics"
            );
        }

        boolean changed =
                screenW > 0
                        && (screenW != width
                        || screenH != height
                        || density != newDensity
                        || rotation != newRotation);

        screenW = width;
        screenH = height;
        density = newDensity;
        rotation = newRotation;

        return changed;
    }

    private boolean resizeCapture(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Invalid capture size"
            );
        }

        if (reader != null
                && sourceW == width
                && sourceH == height
                && captureDensity == density) {
            return false;
        }

        float scale = Math.min(
                1f,
                (float) MAX_CAPTURE_EDGE / Math.max(width, height)
        );

        int newW = Math.max(
                1,
                Math.round(width * scale)
        );

        int newH = Math.max(
                1,
                Math.round(height * scale)
        );

        ImageReader replacement = ImageReader.newInstance(
                newW,
                newH,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
        );

        try {
            if (virtualDisplay == null) {
                /*
                 * Einziger createVirtualDisplay-Aufruf
                 * pro Bildschirmfreigabe.
                 */
                virtualDisplay = projection.createVirtualDisplay(
                        "OpenChepsDiagnostic",
                        newW,
                        newH,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        replacement.getSurface(),
                        null,
                        worker
                );

                if (virtualDisplay == null) {
                    throw new IllegalStateException(
                            "VirtualDisplay unavailable"
                    );
                }

            } else {
                virtualDisplay.setSurface(null);

                virtualDisplay.resize(
                        newW,
                        newH,
                        density
                );

                virtualDisplay.setSurface(
                        replacement.getSurface()
                );
            }

        } catch (RuntimeException error) {
            replacement.close();
            throw error;
        }

        ImageReader old = reader;

        reader = replacement;

        sourceW = width;
        sourceH = height;

        captureW = newW;
        captureH = newH;

        captureDensity = density;

        rgbaPixels = null;
        lastImageTimestamp = 0;

        candidate.reset();

        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;

        if (old != null) {
            old.close();
        }

        Log.i(
                TAG,
                "Capture "
                        + newW
                        + "x"
                        + newH
                        + ", source "
                        + width
                        + "x"
                        + height
        );

        return true;
    }

    private void drainReader() {
        if (reader != null) {
            /*
             * acquireLatestImage verwirft ältere Frames selbst.
             * Keine Endlosschleife gegen einen weiterlaufenden Produzenten.
             */
            try (Image ignored = reader.acquireLatestImage()) {
                /*
                 * Schließen gibt auch während einer Pause
                 * die Puffer frei.
                 */
            }
        }
    }

    private Bitmap imageToBitmap(Image image) {
        if (image.getFormat() != PixelFormat.RGBA_8888
                || image.getPlanes().length != 1) {
            throw new IllegalArgumentException(
                    "Expected one RGBA_8888 plane"
            );
        }

        int width = image.getWidth();
        int height = image.getHeight();

        Rect crop = image.getCropRect();

        if (crop.left != 0
                || crop.top != 0
                || crop.right != width
                || crop.bottom != height) {
            throw new IllegalArgumentException(
                    "Unexpected image crop; "
                            + "screen coordinates would be invalid"
            );
        }

        /*
         * Die Aufnahmegröße ist auf MAX_CAPTURE_EDGE begrenzt.
         */
        int count = width * height;

        if (rgbaPixels == null || rgbaPixels.length != count) {
            rgbaPixels = new int[count];
        }

        Image.Plane plane = image.getPlanes()[0];

        readRgbaPixels(
                plane.getBuffer(),
                width,
                height,
                plane.getRowStride(),
                plane.getPixelStride(),
                rgbaPixels
        );

        return Bitmap.createBitmap(
                rgbaPixels,
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
    }

    private static void readRgbaPixels(
            ByteBuffer input,
            int width,
            int height,
            int rowStride,
            int pixelStride,
            int[] output
    ) {
        if (input == null
                || output == null
                || width <= 0
                || height <= 0
                || pixelStride < 4
                || rowStride <= 0
                || (long) width * height > output.length) {
            throw new IllegalArgumentException(
                    "Invalid RGBA layout"
            );
        }

        long rowBytes =
                (long) (width - 1) * pixelStride + 4;

        long required =
                (long) (height - 1) * rowStride + rowBytes;

        if (rowBytes > rowStride || required > input.limit()) {
            throw new IllegalArgumentException(
                    "Truncated RGBA plane"
            );
        }

        /*
         * Absolute Zugriffe verändern weder Position noch Limit.
         * Die letzte Zeile benötigt keine nachfolgenden Padding-Bytes.
         */
        int index = 0;

        for (int row = 0; row < height; row++) {
            int offset = row * rowStride;

            for (int file = 0; file < width; file++) {
                int red = input.get(offset) & 0xff;
                int green = input.get(offset + 1) & 0xff;
                int blue = input.get(offset + 2) & 0xff;
                int alpha = input.get(offset + 3) & 0xff;

                output[index++] =
                        (alpha << 24)
                                | (red << 16)
                                | (green << 8)
                                | blue;

                offset += pixelStride;
            }
        }
    }

    private boolean canMapToScreen() {
        /*
         * Die Bildschirmposition eines einzeln freigegebenen
         * App-Fensters ist unbekannt. Dort keine vermeintlichen
         * Bildschirmkoordinaten zeichnen.
         */
        return (Build.VERSION.SDK_INT < 34 || receivedContentSize)
                && sourceW == screenW
                && sourceH == screenH
                && captureW > 0
                && captureH > 0;
    }

    private Rect boardBoundsOnScreen(BoardDetector.Board board) {
        float x = (float) screenW / captureW;
        float y = (float) screenH / captureH;

        return new Rect(
                Math.max(
                        0,
                        Math.round(board.left * x)
                ),
                Math.max(
                        0,
                        Math.round(board.top * y)
                ),
                Math.min(
                        screenW,
                        Math.round((board.left + board.side) * x)
                ),
                Math.min(
                        screenH,
                        Math.round((board.top + board.side) * y)
                )
        );
    }

    private static boolean validBoard(
            BoardDetector.Board board,
            int width,
            int height
    ) {
        return board != null
                && finite(board.left)
                && finite(board.top)
                && finite(board.side)
                && finite(board.square)
                && board.left >= 0
                && board.top >= 0
                && board.side > 0
                && board.square > 0
                && board.left + board.side <= width
                && board.top + board.side <= height;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value)
                && !Double.isInfinite(value);
    }

    private static boolean boardMovedTooFar(
            BoardDetector.Board locked,
            BoardDetector.Board detected
    ) {
        float tolerance = Math.max(
                3f,
                locked.square * 0.20f
        );

        return Math.abs(locked.left - detected.left) > tolerance
                || Math.abs(locked.top - detected.top) > tolerance
                || Math.abs(locked.side - detected.side)
                > tolerance * 2f;
    }

    private static boolean validMoveResult(
            MoveAnalyzer.Result move,
            char[][] before,
            char[][] after
    ) {
        if (move == null
                || move.kind == null
                || move.kind == MoveAnalyzer.Kind.UNKNOWN
                || move.kind == MoveAnalyzer.Kind.NONE
                || (move.mover != Side.WHITE
                && move.mover != Side.BLACK)
                || !finite(move.confidence)
                || move.confidence < MIN_MOVE_CONFIDENCE
                || move.confidence > 1.0
                || !isGridValid(before)
                || !isGridValid(after)
                || !validSquare(move.fromRow, move.fromFile)
                || !validSquare(move.toRow, move.toFile)
                || (move.fromRow == move.toRow
                && move.fromFile == move.toFile)) {
            return false;
        }

        char moving =
                before[move.fromRow][move.fromFile];

        char arrived =
                after[move.toRow][move.toFile];

        char target =
                before[move.toRow][move.toFile];

        if (!hasBothKings(before)
                || !hasBothKings(after)
                || !belongsTo(moving, move.mover)
                || !belongsTo(arrived, move.mover)
                || belongsTo(target, move.mover)
                || after[move.fromRow][move.fromFile] != '.') {
            return false;
        }

        int changes = countChanges(before, after);

        /*
         * Plausibilitätsprüfung. Die vollständige Legalitätsprüfung
         * einschließlich Königssicherheit, Rochaderecht und
         * En-passant-Historie gehört weiterhin in MoveAnalyzer.
         */
        switch (move.kind) {
            case QUIET:
                return changes == 2
                        && moving == arrived
                        && target == '.';

            case CAPTURE:
                return changes == 2
                        && moving == arrived
                        && target != '.';

            case PROMOTION:
                return changes == 2
                        && (moving == 'P' || moving == 'p')
                        && "QRBNqrbn".indexOf(arrived) >= 0;

            case CASTLE:
                return changes == 4
                        && moving == arrived
                        && (moving == 'K' || moving == 'k')
                        && target == '.'
                        && move.fromRow == move.toRow
                        && Math.abs(
                                move.fromFile - move.toFile
                        ) == 2;

            case EN_PASSANT:
                return changes == 3
                        && moving == arrived
                        && (moving == 'P' || moving == 'p')
                        && target == '.'
                        && Math.abs(
                                move.fromRow - move.toRow
                        ) == 1
                        && Math.abs(
                                move.fromFile - move.toFile
                        ) == 1
                        && before[move.fromRow][move.toFile]
                        == (move.mover == Side.WHITE ? 'p' : 'P')
                        && after[move.fromRow][move.toFile] == '.';

            default:
                return false;
        }
    }

    private static boolean belongsTo(char piece, Side side) {
        return side == Side.WHITE
                ? "PNBRQK".indexOf(piece) >= 0
                : "pnbrqk".indexOf(piece) >= 0;
    }

    private static boolean hasBothKings(char[][] grid) {
        int white = 0;
        int black = 0;

        for (char[] row : grid) {
            for (char piece : row) {
                if (piece == 'K') {
                    white++;
                }

                if (piece == 'k') {
                    black++;
                }
            }
        }

        return white == 1 && black == 1;
    }

    private static boolean validSquare(int row, int file) {
        return row >= 0
                && row < 8
                && file >= 0
                && file < 8;
    }

    private static String squareNameFor(
            int row,
            int file,
            Side perspective
    ) {
        if (!validSquare(row, file)) {
            return "?";
        }

        if (perspective == Side.BLACK) {
            row = 7 - row;
            file = 7 - file;
        }

        return "" + (char) ('a' + file) + (8 - row);
    }

    private static boolean isGridValid(char[][] grid) {
        if (grid == null || grid.length != 8) {
            return false;
        }

        for (char[] row : grid) {
            if (row == null || row.length != 8) {
                return false;
            }

            for (char piece : row) {
                if (".pnbrqkPNBRQK".indexOf(piece) < 0) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean gridsEqual(char[][] a, char[][] b) {
        if (!isGridValid(a) || !isGridValid(b)) {
            return false;
        }

        for (int row = 0; row < 8; row++) {
            if (!Arrays.equals(a[row], b[row])) {
                return false;
            }
        }

        return true;
    }

    private static char[][] copyGrid(char[][] source) {
        if (!isGridValid(source)) {
            throw new IllegalArgumentException(
                    "Expected an 8x8 chess grid"
            );
        }

        char[][] copy = new char[8][];

        for (int row = 0; row < 8; row++) {
            copy[row] = source[row].clone();
        }

        return copy;
    }

    private static int countChanges(char[][] a, char[][] b) {
        int count = 0;

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                if (a[row][file] != b[row][file]) {
                    count++;
                }
            }
        }

        return count;
    }

    private static char[][] startingGridFor(Side perspective) {
        char[][] base = {
                "rnbqkbnr".toCharArray(),
                "pppppppp".toCharArray(),
                "........".toCharArray(),
                "........".toCharArray(),
                "........".toCharArray(),
                "........".toCharArray(),
                "PPPPPPPP".toCharArray(),
                "RNBQKBNR".toCharArray()
        };

        if (perspective != Side.BLACK) {
            return base;
        }

        char[][] flipped = new char[8][8];

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                flipped[row][file] = base[7 - row][7 - file];
            }
        }

        return flipped;
    }

    private static final class StableGrid {

        private char[][] grid;
        private int repeats;
        private long lastSeen;

        void reset() {
            grid = null;
            repeats = 0;
            lastSeen = 0;
        }

        void expire(long now) {
            if (grid != null
                    && (now < lastSeen
                    || now - lastSeen > MAX_CONFIRMATION_GAP_MS)) {
                reset();
            }
        }

        boolean observe(char[][] observed, long now) {
            expire(now);

            if (!isGridValid(observed)) {
                reset();
                return false;
            }

            if (!gridsEqual(grid, observed)) {
                grid = copyGrid(observed);
                repeats = 1;

            } else {
                repeats = Math.min(
                        REQUIRED_CONFIRMATIONS,
                        repeats + 1
                );
            }

            lastSeen = now;

            return repeats >= REQUIRED_CONFIRMATIONS;
        }
    }

    private static Side opposite(Side side) {
        return side == Side.WHITE ? Side.BLACK : Side.WHITE;
    }

    private static String sideGerman(Side side) {
        if (side == Side.WHITE) {
            return "Weiß";
        }

        if (side == Side.BLACK) {
            return "Schwarz";
        }

        return "?";
    }

    private static String kindGerman(MoveAnalyzer.Kind kind) {
        if (kind == null) {
            return "unklar";
        }

        switch (kind) {
            case QUIET:
                return "normal";

            case CAPTURE:
                return "Schlagen";

            case CASTLE:
                return "Rochade";

            case EN_PASSANT:
                return "En passant";

            case PROMOTION:
                return "Umwandlung";

            case NONE:
                return "keine Änderung";

            default:
                return "unklar";
        }
    }

    private void startInForeground() {
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE
                );

        if (manager == null) {
            throw new IllegalStateException(
                    "NotificationManager unavailable"
            );
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
            );

            manager.createNotificationChannel(channel);
        }

        Intent stopIntent =
                new Intent(this, ScreenCaptureService.class)
                        .setAction(ACTION_STOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= 23) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent stopAction = PendingIntent.getService(
                this,
                1,
                stopIntent,
                pendingFlags
        );

        Notification notification =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("OpenCheps Diagnose")
                        .setContentText("Zugerkennung wird getestet")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .addAction(0, "Stop", stopAction)
                        .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );

        } else {
            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    private void requestStop(@Nullable String message) {
        Runnable action = () -> {
            if (stopping) {
                return;
            }

            stopping = true;

            if (message != null) {
                Toast.makeText(
                        getApplicationContext(),
                        message,
                        Toast.LENGTH_LONG
                ).show();
            }

            stopSelf();
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            main.post(action);
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;

        main.removeCallbacksAndMessages(null);

        if (boundPanel != null) {
            safeRelease(
                    "Panel listener",
                    () -> boundPanel.setListener(DETACHED_LISTENER)
            );

            boundPanel = null;
        }

        safeRelease(
                "Overlay",
                OverlayService::clearAll
        );

        if (overlayStarted) {
            safeRelease(
                    "Overlay service",
                    () -> stopService(
                            new Intent(this, OverlayService.class)
                    )
            );
        }

        safeRelease(
                "Foreground notification",
                () -> stopForeground(true)
        );

        Handler target = worker;
        HandlerThread thread = workerThread;

        if (target != null) {
            target.removeCallbacksAndMessages(null);

            /*
             * Eine laufende Auswertung darf Image und Bitmap zuerst
             * schließen. Kein join() auf dem Hauptthread und keine
             * Ressourcenfreigabe während der Verarbeitung.
             */
            boolean posted = target.post(() -> {
                try {
                    releaseCaptureResources();
                } finally {
                    thread.quitSafely();
                }
            });

            if (!posted) {
                Log.e(
                        TAG,
                        "Worker no longer accepts cleanup"
                );

                if (!thread.isAlive()) {
                    releaseCaptureResources();
                }
            }

        } else if (thread != null) {
            thread.quitSafely();
        }

        super.onDestroy();
    }

    private void releaseCaptureResources() {
        if (resourcesReleased) {
            return;
        }

        resourcesReleased = true;

        MediaProjection oldProjection = projection;
        VirtualDisplay oldDisplay = virtualDisplay;
        ImageReader oldReader = reader;

        projection = null;
        virtualDisplay = null;
        reader = null;

        classifier = null;
        rgbaPixels = null;

        confirmedGrid = null;
        lockedBoard = null;

        candidate.reset();

        if (oldProjection != null) {
            safeRelease(
                    "Projection callback",
                    () -> oldProjection.unregisterCallback(
                            projectionCallback
                    )
            );
        }

        if (oldDisplay != null) {
            safeRelease(
                    "VirtualDisplay",
                    oldDisplay::release
            );
        }

        if (oldReader != null) {
            safeRelease(
                    "ImageReader",
                    oldReader::close
            );
        }

        if (oldProjection != null) {
            safeRelease(
                    "MediaProjection",
                    oldProjection::stop
            );
        }
    }

    private static void safeRelease(
            String name,
            Runnable action
    ) {
        try {
            action.run();

        } catch (RuntimeException error) {
            Log.w(
                    TAG,
                    "Could not release " + name,
                    error
            );
        }
    }
}
