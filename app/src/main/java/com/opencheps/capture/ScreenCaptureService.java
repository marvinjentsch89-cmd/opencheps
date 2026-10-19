package com.opencheps.capture;

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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.opencheps.R;
import com.opencheps.engine.StockfishEngine;
import com.opencheps.overlay.ArrowOverlayView;
import com.opencheps.overlay.FloatingPanel;
import com.opencheps.overlay.OverlayService;
import com.opencheps.util.Prefs;
import com.opencheps.util.Side;
import com.opencheps.vision.BoardDetector;
import com.opencheps.vision.FenBuilder;
import com.opencheps.vision.MoveAnalyzer;
import com.opencheps.vision.PieceClassifier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScreenCaptureService extends Service {

    private static final String TAG = "OC/Capture";
    private static final String CHANNEL_ID = "opencheps_capture";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_USER_SIDE = "user_side";
    public static final String ACTION_STOP = "com.opencheps.STOP";

    private static final long SAMPLE_PERIOD_MS = 400;
    private static final long MAX_CONFIRM_GAP_MS = 1600;

    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final int LOST_BOARD_THRESHOLD = 6;

    private static final int BEST_ARROW_COLOR = 0xFF00C853;
    private static final float BEST_ARROW_WIDTH = 20f;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;

    private HandlerThread workerThread;
    private Handler worker;
    private Handler main;

    private PieceClassifier classifier;

    private StockfishEngine engine;
    private boolean engineReady = false;

    private int captureW;
    private int captureH;
    private int density;

    private int screenW;
    private int screenH;

    private float capToScreenX = 1f;
    private float capToScreenY = 1f;

    private volatile boolean paused = false;
    private volatile boolean busy = false;

    private boolean started = false;

    private Side userSide = Side.WHITE;

    /*
     * Welche Farbe nach unserem bestätigten
     * Spielverlauf am Zug ist.
     */
    private Side expectedSide = Side.WHITE;

    /*
     * Letzte bestätigte interne Stellung.
     */
    private char[][] confirmedGrid;

    /*
     * Nach Kalibrierung bleiben die Brettkoordinaten
     * fest, damit sie nicht ständig springen.
     */
    private BoardDetector.Board lockedBoard;

    private int lostBoardCount = 0;

    /*
     * Nicht das komplette erkannte Brett muss zweimal
     * gleich sein. Nur derselbe erkannte Zug.
     */
    private String pendingMoveKey = null;
    private int pendingMoveCount = 0;
    private long pendingMoveAt = 0;

    private String lastMoveText = "";

    private String lastEngineFen = "";

    private List<ArrowOverlayView.Arrow> currentArrows =
            new ArrayList<>();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent != null
                && ACTION_STOP.equals(intent.getAction())) {

            stopSelf();
            return START_NOT_STICKY;
        }

        if (started) {
            return START_NOT_STICKY;
        }

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();

        int resultCode =
                intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        0
                );

        Intent data =
                intent.getParcelableExtra(
                        EXTRA_RESULT_DATA
                );

        if (data == null) {

            stopSelf();
            return START_NOT_STICKY;
        }

        String side =
                intent.getStringExtra(
                        EXTRA_USER_SIDE
                );

        if (side == null) {
            side = Prefs.userSide(this);
        }

        userSide =
                "b".equals(side)
                        ? Side.BLACK
                        : Side.WHITE;

        main =
                new Handler(
                        getMainLooper()
                );

        workerThread =
                new HandlerThread(
                        "oc-worker"
                );

        workerThread.start();

        worker =
                new Handler(
                        workerThread.getLooper()
                );

        classifier =
                new PieceClassifier();

        engine =
                new StockfishEngine();

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE
                        );

        projection =
                manager == null
                        ? null
                        : manager.getMediaProjection(
                                resultCode,
                                data
                        );

        if (projection == null) {

            stopSelf();
            return START_NOT_STICKY;
        }

        projection.registerCallback(
                new MediaProjection.Callback() {

                    @Override
                    public void onStop() {
                        stopSelf();
                    }

                },
                worker
        );

        try {

            setupVirtualDisplay();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Virtual display failed",
                    e
            );

            stopSelf();
            return START_NOT_STICKY;
        }

        startService(
                new Intent(
                        this,
                        OverlayService.class
                )
        );

        started = true;

        wirePanel();

        /*
         * Engine auf demselben Worker starten.
         * Die Bilderkennung beginnt erst danach.
         */
        worker.post(() -> {

            panelStatus(
                    "Stockfish startet…"
            );

            try {

                engine.start(
                        getApplicationContext()
                );

                engineReady = true;

                panelStatus(
                        "Stockfish bereit · Startstellung zeigen"
                );

            } catch (Exception e) {

                engineReady = false;

                Log.e(
                        TAG,
                        "Stockfish start failed",
                        e
                );

                panelStatus(
                        "Stockfish-Fehler"
                );

                panelEval(
                        "Engine konnte nicht gestartet werden"
                );
            }
        });

        worker.postDelayed(
                sampleLoop,
                800
        );

        return START_NOT_STICKY;
    }

    private void wirePanel() {

        if (main == null) {
            return;
        }

        main.post(
                () -> tryWirePanel(0)
        );
    }

    private void tryWirePanel(
            int attempt
    ) {

        FloatingPanel panel =
                OverlayService.panel();

        if (panel == null) {

            if (attempt < 12
                    && main != null) {

                main.postDelayed(
                        () ->
                                tryWirePanel(
                                        attempt + 1
                                ),
                        250
                );
            }

            return;
        }

        panel.placeBottomLeft(
                screenW,
                screenH
        );

        panel.setUserSide(
                userSide
        );

        panel.setListener(
                new FloatingPanel.Listener() {

                    @Override
                    public void onPauseToggle(
                            boolean isPaused
                    ) {

                        paused = isPaused;

                        if (paused) {

                            clearArrows();

                            panelStatus(
                                    "Pausiert"
                            );

                        } else {

                            resetPendingMove();

                            lastEngineFen = "";

                            panelStatus(
                                    "Fortgesetzt"
                            );
                        }
                    }

                    @Override
                    public void onStop() {

                        stopSelf();
                    }

                    @Override
                    public void onRecalibrate() {

                        if (worker == null) {
                            return;
                        }

                        worker.post(() -> {

                            classifier.reset();

                            resetGameState();

                            clearAll();

                            panelStatus(
                                    "Startstellung zeigen"
                            );

                            panelEval("");
                        });
                    }

                    @Override
                    public void onSideChange(
                            Side newSide
                    ) {

                        Prefs.setUserSide(
                                getApplicationContext(),
                                newSide == Side.BLACK
                                        ? "b"
                                        : "w"
                        );

                        if (worker == null) {
                            return;
                        }

                        worker.post(() -> {

                            userSide =
                                    newSide;

                            classifier.reset();

                            resetGameState();

                            clearAll();

                            panelStatus(
                                    "Farbe geändert · neu kalibrieren"
                            );

                            panelEval("");
                        });
                    }
                }
        );
    }

    private void resetGameState() {

        confirmedGrid = null;

        lockedBoard = null;

        expectedSide =
                Side.WHITE;

        lostBoardCount = 0;

        lastMoveText = "";

        lastEngineFen = "";

        currentArrows.clear();

        resetPendingMove();
    }

    private void resetPendingMove() {

        pendingMoveKey = null;
        pendingMoveCount = 0;
        pendingMoveAt = 0;
    }

    private final Runnable sampleLoop =
            new Runnable() {

                @Override
                public void run() {

                    try {

                        if (!paused) {

                            sampleOnce();

                        } else {

                            drainReader();
                        }

                    } catch (Throwable t) {

                        Log.e(
                                TAG,
                                "sample error",
                                t
                        );

                        panelStatus(
                                "Diagnose-Fehler"
                        );
                    }

                    if (worker != null) {

                        worker.postDelayed(
                                this,
                                SAMPLE_PERIOD_MS
                        );
                    }
                }
            };

    private void drainReader() {

        if (reader == null) {
            return;
        }

        while (true) {

            Image image;

            try {

                image =
                        reader.acquireNextImage();

            } catch (Exception e) {

                break;
            }

            if (image == null) {
                break;
            }

            image.close();
        }
    }

    private void sampleOnce() {

        if (busy
                || reader == null) {

            return;
        }

        Image image;

        try {

            image =
                    reader.acquireLatestImage();

        } catch (Exception e) {

            return;
        }

        if (image == null) {
            return;
        }

        busy = true;

        Bitmap bitmap = null;

        try {

            bitmap =
                    imageToBitmap(
                            image
                    );

            image.close();
            image = null;

            BoardDetector.Board detected =
                    BoardDetector.locate(
                            bitmap
                    );

            if (detected == null) {

                resetPendingMove();

                lostBoardCount++;

                if (lostBoardCount
                        >= LOST_BOARD_THRESHOLD) {

                    panelStatus(
                            "Brett nicht erkannt"
                    );

                    clearArrows();
                }

                return;
            }

            lostBoardCount = 0;

            BoardDetector.Board board =
                    detected;

            if (classifier.isTrained()
                    && lockedBoard != null) {

                if (boardMovedTooFar(
                        lockedBoard,
                        detected
                )) {

                    resetPendingMove();

                    clearArrows();

                    panelStatus(
                            "Brett verschoben · neu kalibrieren"
                    );

                    panelEval("");

                    return;
                }

                board =
                        lockedBoard;
            }

            setBoardBounds(
                    boardBoundsOnScreen(
                            board
                    )
            );

            /*
             * KALIBRIERUNG
             */
            if (!classifier.isTrained()) {

                calibrate(
                        bitmap,
                        board
                );

                return;
            }

            char[][] observed =
                    classifier.classifyBoard(
                            bitmap,
                            board
                    );

            if (!validGrid(observed)
                    || !validGrid(
                    confirmedGrid
            )) {

                resetPendingMove();

                panelStatus(
                        "Figuren nicht eindeutig erkannt"
                );

                return;
            }

            /*
             * Keine Änderung.
             */
            if (gridsEqual(
                    observed,
                    confirmedGrid
            )) {

                resetPendingMove();

                /*
                 * Falls aus irgendeinem Grund noch kein
                 * Stockfish-Ergebnis für die aktuelle
                 * bestätigte Stellung vorhanden ist.
                 */
                if (expectedSide == userSide
                        && engineReady
                        && lastEngineFen.isEmpty()) {

                    queryEngineAndRender(
                            board
                    );

                    return;
                }

                if (expectedSide != userSide) {

                    clearArrows();

                    panelStatus(
                            "Gegner am Zug"
                    );

                    panelEval(
                            lastMoveText.isEmpty()
                                    ? "Warte auf Gegenzug"
                                    : "LETZTER ZUG: "
                                    + lastMoveText
                                    + "\nWarte auf Gegenzug"
                    );
                }

                return;
            }

            /*
             * Analyzer arbeitet auf Kopien.
             *
             * Bei erfolgreicher Erkennung wird proposal
             * zur sauberen Folgestellung umgebaut.
             */
            char[][] proposal =
                    copyGrid(
                            observed
                    );

            MoveAnalyzer.Result move =
                    MoveAnalyzer.analyze(
                            copyGrid(
                                    confirmedGrid
                            ),
                            proposal
                    );

            if (!usableMove(move)) {

                resetPendingMove();

                clearArrows();

                lastEngineFen = "";

                panelStatus(
                        "Position nicht sicher"
                );

                panelEval(
                        "ÄNDERUNGEN: "
                                + countChanges(
                                confirmedGrid,
                                observed
                        )
                                + "\nERWARTET: "
                                + sideGerman(
                                expectedSide
                        )
                );

                return;
            }

            /*
             * Nur derselbe Zug muss zweimal erkannt werden.
             */
            String moveKey =
                    move.mover
                            + ":"
                            + move.fromRow
                            + ","
                            + move.fromFile
                            + ">"
                            + move.toRow
                            + ","
                            + move.toFile
                            + ":"
                            + move.kind;

            long now =
                    SystemClock.uptimeMillis();

            if (!moveKey.equals(
                    pendingMoveKey
            )
                    || now - pendingMoveAt
                    > MAX_CONFIRM_GAP_MS) {

                pendingMoveKey =
                        moveKey;

                pendingMoveCount =
                        1;

                pendingMoveAt =
                        now;

                panelStatus(
                        "Zug erkannt · bestätige…"
                );

                panelEval(
                        sideGerman(
                                move.mover
                        )
                                + " "
                                + squareName(
                                move.fromRow,
                                move.fromFile
                        )
                                + " → "
                                + squareName(
                                move.toRow,
                                move.toFile
                        )
                );

                return;
            }

            pendingMoveCount++;

            pendingMoveAt =
                    now;

            if (pendingMoveCount
                    < REQUIRED_CONFIRMATIONS) {

                return;
            }

            /*
             * ZUG BESTÄTIGT
             */
            boolean syncCorrected =
                    move.mover
                            != expectedSide;

            String from =
                    squareName(
                            move.fromRow,
                            move.fromFile
                    );

            String to =
                    squareName(
                            move.toRow,
                            move.toFile
                    );

            String mover =
                    sideGerman(
                            move.mover
                    );

            confirmedGrid =
                    copyGrid(
                            proposal
                    );

            /*
             * Erst nach einem bestätigten Zug wird
             * der Klassifikator fortgeschrieben.
             */
            classifier.commitConfirmed(
                    confirmedGrid,
                    bitmap,
                    board
            );

            expectedSide =
                    opposite(
                            move.mover
                    );

            lastMoveText =
                    mover
                            + " "
                            + from
                            + "→"
                            + to;

            lastEngineFen = "";

            resetPendingMove();

            int confidence =
                    (int) Math.round(
                            Math.max(
                                    0.0,
                                    Math.min(
                                            1.0,
                                            move.confidence
                                    )
                            ) * 100.0
                    );

            Log.i(
                    TAG,
                    "MOVE "
                            + lastMoveText
                            + " next="
                            + expectedSide
                            + " confidence="
                            + confidence
                            + " sync="
                            + syncCorrected
            );

            if (expectedSide
                    == userSide) {

                panelStatus(
                        syncCorrected
                                ? "SYNC KORRIGIERT · Stockfish rechnet…"
                                : "Zug erkannt · Stockfish rechnet…"
                );

                queryEngineAndRender(
                        board
                );

            } else {

                clearArrows();

                panelStatus(
                        syncCorrected
                                ? "SYNC KORRIGIERT · Gegner am Zug"
                                : "Gegner am Zug"
                );

                panelEval(
                        "ERKANNT: "
                                + lastMoveText
                                + "\nDANACH: "
                                + sideGerman(
                                expectedSide
                        )
                                + " am Zug"
                                + "\nSICHER: "
                                + confidence
                                + "%"
                );
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "sampleOnce failed",
                    e
            );

            panelStatus(
                    "Fehler bei Zugerkennung"
            );

        } finally {

            if (bitmap != null
                    && !bitmap.isRecycled()) {

                bitmap.recycle();
            }

            if (image != null) {

                try {

                    image.close();

                } catch (Exception ignored) {
                }
            }

            busy = false;
        }
    }

    private void calibrate(
            Bitmap bitmap,
            BoardDetector.Board board
    ) {

        boolean ok =
                classifier.tryBootstrap(
                        bitmap,
                        board
                );

        if (!ok) {

            panelStatus(
                    "Startstellung zeigen"
            );

            setFrameColor(
                    0x66FFB300
            );

            return;
        }

        Side detectedSide =
                classifier.bootstrapSide();

        if (detectedSide
                != userSide) {

            classifier.reset();

            panelStatus(
                    "Brett/Farbe stimmt nicht"
            );

            panelEval(
                    "Farbe wählen oder Brett drehen"
            );

            setFrameColor(
                    0x66D32F2F
            );

            return;
        }

        lockedBoard =
                board;

        confirmedGrid =
                startingGridFor(
                        userSide
                );

        expectedSide =
                Side.WHITE;

        lastMoveText = "";

        lastEngineFen = "";

        resetPendingMove();

        setFrameColor(
                0x6600C853
        );

        if (!engineReady) {

            panelStatus(
                    "Kalibriert · Stockfish startet"
            );

            return;
        }

        if (expectedSide
                == userSide) {

            queryEngineAndRender(
                    board
            );

        } else {

            clearArrows();

            panelStatus(
                    "Kalibriert · Gegner am Zug"
            );

            panelEval(
                    "Warte auf Gegenzug"
            );
        }
    }

    /*
     * ---------------------------------------------------------
     * STOCKFISH
     * ---------------------------------------------------------
     */

    private void queryEngineAndRender(
            BoardDetector.Board board
    ) {

        if (!engineReady
                || engine == null
                || confirmedGrid == null
                || classifier.bootstrapSide() == null) {

            return;
        }

        /*
         * Nur für die gewählte Testseite
         * eine Empfehlung anzeigen.
         */
        if (expectedSide
                != userSide) {

            clearArrows();

            return;
        }

        String fen =
                FenBuilder.build(
                        confirmedGrid,
                        classifier.bootstrapSide(),
                        expectedSide
                );

        if (fen == null) {

            clearArrows();

            panelStatus(
                    "Interne Stellung ungültig"
            );

            panelEval("");

            return;
        }

        if (fen.equals(
                lastEngineFen
        )
                && !currentArrows.isEmpty()) {

            pushCurrentArrows(
                    board
            );

            return;
        }

        panelStatus(
                "Stockfish rechnet…"
        );

        try {

            int thinkMs =
                    Prefs.thinkMs(
                            getApplicationContext()
                    );

            List<StockfishEngine.Pv> moves =
                    engine.topMoves(
                            fen,
                            thinkMs,
                            1
                    );

            if (moves == null
                    || moves.isEmpty()) {

                clearArrows();

                panelStatus(
                        "Kein Stockfish-Zug"
                );

                panelEval("");

                return;
            }

            StockfishEngine.Pv best =
                    moves.get(0);

            if (best.uci == null
                    || best.uci.length() < 4) {

                clearArrows();

                panelStatus(
                        "Stockfish-Antwort ungültig"
                );

                return;
            }

            ArrowOverlayView.Arrow arrow =
                    arrowForUci(
                            best.uci,
                            board
                    );

            if (arrow == null) {

                clearArrows();

                panelStatus(
                        "Zug konnte nicht gezeichnet werden"
                );

                return;
            }

            currentArrows.clear();

            currentArrows.add(
                    arrow
            );

            lastEngineFen =
                    fen;

            pushCurrentArrows(
                    board
            );

            String bestText =
                    formatUci(
                            best.uci
                    );

            panelStatus(
                    "Bereit"
            );

            String info =
                    "BESTER ZUG: "
                            + bestText
                            + "\nINTERN: "
                            + sideGerman(
                            expectedSide
                    )
                            + " am Zug";

            if (!lastMoveText.isEmpty()) {

                info +=
                        "\nLETZTER ZUG: "
                                + lastMoveText;
            }

            panelEval(
                    info
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Stockfish query failed",
                    e
            );

            clearArrows();

            lastEngineFen = "";

            panelStatus(
                    "Stockfish-Fehler"
            );

            panelEval(
                    "Engine-Abfrage fehlgeschlagen"
            );
        }
    }

    private ArrowOverlayView.Arrow arrowForUci(
            String uci,
            BoardDetector.Board board
    ) {

        if (uci == null
                || uci.length() < 4) {

            return null;
        }

        int fromFile =
                uci.charAt(0) - 'a';

        int fromRank =
                uci.charAt(1) - '0';

        int toFile =
                uci.charAt(2) - 'a';

        int toRank =
                uci.charAt(3) - '0';

        if (fromFile < 0
                || fromFile > 7
                || toFile < 0
                || toFile > 7
                || fromRank < 1
                || fromRank > 8
                || toRank < 1
                || toRank > 8) {

            return null;
        }

        int fromRow =
                8 - fromRank;

        int toRow =
                8 - toRank;

        int screenFromRow =
                fromRow;

        int screenFromFile =
                fromFile;

        int screenToRow =
                toRow;

        int screenToFile =
                toFile;

        if (classifier.bootstrapSide()
                == Side.BLACK) {

            screenFromRow =
                    7 - fromRow;

            screenFromFile =
                    7 - fromFile;

            screenToRow =
                    7 - toRow;

            screenToFile =
                    7 - toFile;
        }

        Rect bounds =
                boardBoundsOnScreen(
                        board
                );

        float square =
                bounds.width()
                        / 8f;

        float sx =
                bounds.left
                        + (
                        screenFromFile
                                + 0.5f
                ) * square;

        float sy =
                bounds.top
                        + (
                        screenFromRow
                                + 0.5f
                ) * square;

        float ex =
                bounds.left
                        + (
                        screenToFile
                                + 0.5f
                ) * square;

        float ey =
                bounds.top
                        + (
                        screenToRow
                                + 0.5f
                ) * square;

        return new ArrowOverlayView.Arrow(
                sx,
                sy,
                ex,
                ey,
                BEST_ARROW_COLOR,
                BEST_ARROW_WIDTH,
                null
        );
    }

    private String formatUci(
            String uci
    ) {

        if (uci == null
                || uci.length() < 4) {

            return "?";
        }

        String text =
                uci.substring(
                        0,
                        2
                )
                        + " → "
                        + uci.substring(
                        2,
                        4
                );

        if (uci.length() >= 5) {

            char promotion =
                    Character.toUpperCase(
                            uci.charAt(4)
                    );

            text +=
                    "="
                            + promotion;
        }

        return text;
    }

    private void pushCurrentArrows(
            BoardDetector.Board board
    ) {

        if (main == null) {
            return;
        }

        final Rect bounds =
                boardBoundsOnScreen(
                        board
                );

        final List<ArrowOverlayView.Arrow> copy =
                new ArrayList<>(
                        currentArrows
                );

        main.post(
                () ->
                        OverlayService.pushArrows(
                                copy,
                                bounds
                        )
        );
    }

    private void clearArrows() {

        currentArrows.clear();

        if (main != null) {

            main.post(
                    OverlayService::clearArrowsOnly
            );
        }
    }

    private void clearAll() {

        currentArrows.clear();

        if (main != null) {

            main.post(
                    OverlayService::clearAll
            );
        }
    }

    /*
     * ---------------------------------------------------------
     * ZUG-/BRETT-HILFEN
     * ---------------------------------------------------------
     */

    private boolean usableMove(
            MoveAnalyzer.Result move
    ) {

        if (move == null
                || move.kind
                == MoveAnalyzer.Kind.UNKNOWN
                || move.kind
                == MoveAnalyzer.Kind.NONE
                || move.mover == null) {

            return false;
        }

        return move.fromRow >= 0
                && move.fromRow < 8
                && move.fromFile >= 0
                && move.fromFile < 8
                && move.toRow >= 0
                && move.toRow < 8
                && move.toFile >= 0
                && move.toFile < 8
                && move.confidence > 0.0;
    }

    private String squareName(
            int row,
            int file
    ) {

        if (classifier == null
                || classifier.bootstrapSide()
                == null) {

            return "?";
        }

        int r =
                row;

        int f =
                file;

        if (classifier.bootstrapSide()
                == Side.BLACK) {

            r =
                    7 - r;

            f =
                    7 - f;
        }

        char fileChar =
                (char) (
                        'a' + f
                );

        int rank =
                8 - r;

        return ""
                + fileChar
                + rank;
    }

    private String sideGerman(
            Side side
    ) {

        if (side == Side.WHITE) {
            return "Weiß";
        }

        if (side == Side.BLACK) {
            return "Schwarz";
        }

        return "?";
    }

    private Side opposite(
            Side side
    ) {

        return side == Side.WHITE
                ? Side.BLACK
                : Side.WHITE;
    }

    private int countChanges(
            char[][] a,
            char[][] b
    ) {

        if (!validGrid(a)
                || !validGrid(b)) {

            return 64;
        }

        int count =
                0;

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                if (normalize(
                        a[r][f]
                )
                        != normalize(
                        b[r][f]
                )) {

                    count++;
                }
            }
        }

        return count;
    }

    private char normalize(
            char c
    ) {

        return c == 0
                ? '.'
                : c;
    }

    private boolean validGrid(
            char[][] grid
    ) {

        if (grid == null
                || grid.length != 8) {

            return false;
        }

        for (int r = 0;
             r < 8;
             r++) {

            if (grid[r] == null
                    || grid[r].length != 8) {

                return false;
            }
        }

        return true;
    }

    private boolean gridsEqual(
            char[][] a,
            char[][] b
    ) {

        if (!validGrid(a)
                || !validGrid(b)) {

            return false;
        }

        for (int r = 0;
             r < 8;
             r++) {

            if (!Arrays.equals(
                    a[r],
                    b[r]
            )) {

                return false;
            }
        }

        return true;
    }

    private char[][] copyGrid(
            char[][] source
    ) {

        if (!validGrid(source)) {
            return null;
        }

        char[][] copy =
                new char[8][8];

        for (int r = 0;
             r < 8;
             r++) {

            System.arraycopy(
                    source[r],
                    0,
                    copy[r],
                    0,
                    8
            );
        }

        return copy;
    }

    private char[][] startingGridFor(
            Side perspective
    ) {

        char[][] base = {

                {
                        'r','n','b','q',
                        'k','b','n','r'
                },

                {
                        'p','p','p','p',
                        'p','p','p','p'
                },

                {
                        '.','.','.','.',
                        '.','.','.','.'
                },

                {
                        '.','.','.','.',
                        '.','.','.','.'
                },

                {
                        '.','.','.','.',
                        '.','.','.','.'
                },

                {
                        '.','.','.','.',
                        '.','.','.','.'
                },

                {
                        'P','P','P','P',
                        'P','P','P','P'
                },

                {
                        'R','N','B','Q',
                        'K','B','N','R'
                }
        };

        if (perspective
                == Side.WHITE) {

            return base;
        }

        char[][] flipped =
                new char[8][8];

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                flipped[r][f] =
                        base[
                                7 - r
                        ][
                                7 - f
                        ];
            }
        }

        return flipped;
    }

    private boolean boardMovedTooFar(
            BoardDetector.Board locked,
            BoardDetector.Board detected
    ) {

        float tolerance =
                Math.max(
                        6f,
                        locked.square
                                * 0.40f
                );

        return Math.abs(
                locked.left
                        - detected.left
        ) > tolerance
                || Math.abs(
                locked.top
                        - detected.top
        ) > tolerance
                || Math.abs(
                locked.side
                        - detected.side
        ) > tolerance * 2f;
    }

    private Rect boardBoundsOnScreen(
            BoardDetector.Board board
    ) {

        return new Rect(

                Math.round(
                        board.left
                                * capToScreenX
                ),

                Math.round(
                        board.top
                                * capToScreenY
                ),

                Math.round(
                        (
                                board.left
                                        + board.side
                        )
                                * capToScreenX
                ),

                Math.round(
                        (
                                board.top
                                        + board.side
                        )
                                * capToScreenY
                )
        );
    }

    /*
     * ---------------------------------------------------------
     * OVERLAY / PANEL
     * ---------------------------------------------------------
     */

    private void setBoardBounds(
            Rect bounds
    ) {

        if (main != null) {

            main.post(
                    () ->
                            OverlayService.setBoardBounds(
                                    bounds
                            )
            );
        }
    }

    private void setFrameColor(
            int color
    ) {

        if (main != null) {

            main.post(
                    () ->
                            OverlayService
                                    .setBoardFrameColor(
                                            color
                                    )
            );
        }
    }

    private void panelStatus(
            String text
    ) {

        Runnable action =
                () -> {

                    FloatingPanel panel =
                            OverlayService.panel();

                    if (panel != null) {
                        panel.setStatus(text);
                    }
                };

        if (main != null) {
            main.post(action);
        }
    }

    private void panelEval(
            String text
    ) {

        Runnable action =
                () -> {

                    FloatingPanel panel =
                            OverlayService.panel();

                    if (panel != null) {
                        panel.setEval(text);
                    }
                };

        if (main != null) {
            main.post(action);
        }
    }

    /*
     * ---------------------------------------------------------
     * SCREEN CAPTURE
     * ---------------------------------------------------------
     */

    @SuppressWarnings("deprecation")
    private void setupVirtualDisplay() {

        DisplayMetrics metrics =
                new DisplayMetrics();

        WindowManager windowManager =
                (WindowManager)
                        getSystemService(
                                WINDOW_SERVICE
                        );

        windowManager
                .getDefaultDisplay()
                .getRealMetrics(
                        metrics
                );

        screenW =
                metrics.widthPixels;

        screenH =
                metrics.heightPixels;

        density =
                metrics.densityDpi;

        float maxEdge =
                Math.max(
                        screenW,
                        screenH
                );

        float scale =
                Math.min(
                        1f,
                        800f / maxEdge
                );

        captureW =
                Math.max(
                        1,
                        Math.round(
                                screenW * scale
                        )
                );

        captureH =
                Math.max(
                        1,
                        Math.round(
                                screenH * scale
                        )
                );

        capToScreenX =
                (float) screenW
                        / captureW;

        capToScreenY =
                (float) screenH
                        / captureH;

        reader =
                ImageReader.newInstance(
                        captureW,
                        captureH,
                        PixelFormat.RGBA_8888,
                        4
                );

        virtualDisplay =
                projection.createVirtualDisplay(
                        "OpenCheps",
                        captureW,
                        captureH,
                        density,
                        DisplayManager
                                .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(),
                        null,
                        worker
                );
    }

    private Bitmap imageToBitmap(
            Image image
    ) {

        Image.Plane plane =
                image.getPlanes()[0];

        ByteBuffer buffer =
                plane.getBuffer();

        int width =
                image.getWidth();

        int height =
                image.getHeight();

        int pixelStride =
                plane.getPixelStride();

        int rowStride =
                plane.getRowStride();

        int rowPadding =
                rowStride
                        - pixelStride
                        * width;

        int bitmapWidth =
                width
                        + rowPadding
                        / pixelStride;

        Bitmap raw =
                Bitmap.createBitmap(
                        bitmapWidth,
                        height,
                        Bitmap.Config.ARGB_8888
                );

        buffer.rewind();

        raw.copyPixelsFromBuffer(
                buffer
        );

        if (bitmapWidth == width) {
            return raw;
        }

        Bitmap cropped =
                Bitmap.createBitmap(
                        raw,
                        0,
                        0,
                        width,
                        height
                );

        raw.recycle();

        return cropped;
    }

    /*
     * ---------------------------------------------------------
     * FOREGROUND SERVICE
     * ---------------------------------------------------------
     */

    private void startInForeground() {

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE
                        );

        if (Build.VERSION.SDK_INT
                >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            getString(
                                    R.string.notif_channel
                            ),
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            manager.createNotificationChannel(
                    channel
            );
        }

        Intent stopIntent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                )
                        .setAction(
                                ACTION_STOP
                        );

        PendingIntent stopPending =
                PendingIntent.getService(
                        this,
                        1,
                        stopIntent,
                        PendingIntent
                                        .FLAG_UPDATE_CURRENT
                                | PendingIntent
                                        .FLAG_IMMUTABLE
                );

        Notification notification =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_ID
                )
                        .setContentTitle(
                                "OpenCheps Test"
                        )
                        .setContentText(
                                "Zugerkennung + Stockfish aktiv"
                        )
                        .setSmallIcon(
                                R.mipmap.ic_launcher
                        )
                        .setOngoing(true)
                        .addAction(
                                0,
                                "Stop",
                                stopPending
                        )
                        .build();

        if (Build.VERSION.SDK_INT
                >= 29) {

            startForeground(
                    7,
                    notification,
                    ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );

        } else {

            startForeground(
                    7,
                    notification
            );
        }
    }

    @Override
    public void onDestroy() {

        started = false;
        paused = true;

        if (worker != null) {

            worker.removeCallbacksAndMessages(
                    null
            );
        }

        if (engine != null) {

            try {

                engine.stop();

            } catch (Exception ignored) {
            }

            engine = null;
        }

        engineReady = false;

        if (virtualDisplay != null) {

            try {

                virtualDisplay.release();

            } catch (Exception ignored) {
            }

            virtualDisplay = null;
        }

        if (reader != null) {

            try {

                reader.close();

            } catch (Exception ignored) {
            }

            reader = null;
        }

        if (projection != null) {

            try {

                projection.stop();

            } catch (Exception ignored) {
            }

            projection = null;
        }

        if (workerThread != null) {

            workerThread.quitSafely();

            workerThread = null;
        }

        stopService(
                new Intent(
                        this,
                        OverlayService.class
                )
        );

        super.onDestroy();
    }
}
