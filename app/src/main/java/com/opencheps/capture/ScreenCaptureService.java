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

    // Nur EIN grüner Pfeil.
    private static final int BEST_ARROW_COLOR = 0xFF00C853;
    private static final float BEST_ARROW_WIDTH = 20f;

    // Erst Stabilität testen. Geschwindigkeit optimieren wir danach.
    private static final long SAMPLE_PERIOD_MS = 500;

    private static final int LOST_BOARD_THRESHOLD = 8;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;

    private HandlerThread workerThread;
    private Handler worker;

    private StockfishEngine engine;
    private boolean engineReady;

    private PieceClassifier classifier;

    private int captureW;
    private int captureH;
    private int density;

    private int screenW;
    private int screenH;

    private float capToScreenX = 1f;
    private float capToScreenY = 1f;

    private volatile boolean busy = false;
    private volatile boolean paused = false;

    private Side userSide = Side.WHITE;

    private char[][] confirmedGrid;
    private char[][] candidateGrid;

    private int candidateRepeats;

    private Side observedStm = Side.WHITE;

    private String lastEngineFen = "";

    private int lostBoardCount = 0;

    // Nach erfolgreicher Kalibrierung wird die Brettposition festgehalten.
    // Dadurch springt das Brett nicht ständig ein paar Pixel.
    private BoardDetector.Board lockedBoard;

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

        startInForeground();

        int resultCode =
                intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent data =
                intent.getParcelableExtra(EXTRA_RESULT_DATA);

        String sideStr =
                intent.getStringExtra(EXTRA_USER_SIDE);

        if (sideStr == null) {
            sideStr = Prefs.userSide(this);
        }

        userSide =
                "b".equals(sideStr)
                        ? Side.BLACK
                        : Side.WHITE;

        MediaProjectionManager mpm =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        projection =
                mpm.getMediaProjection(
                        resultCode,
                        data
                );

        if (projection == null) {

            Log.e(
                    TAG,
                    "MediaProjection acquisition failed"
            );

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
                null
        );

        workerThread =
                new HandlerThread(
                        "oc-worker",
                        Thread.NORM_PRIORITY
                );

        workerThread.start();

        worker =
                new Handler(
                        workerThread.getLooper()
                );

        engine = new StockfishEngine();
        classifier = new PieceClassifier();

        worker.post(() -> {

            try {

                engine.start(
                        getApplicationContext()
                );

                engineReady = true;

                panelStatus(
                        "Engine bereit"
                );

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "engine start failed",
                        e
                );

                panelStatus(
                        "Engine-Fehler"
                );
            }
        });

        setupVirtualDisplay();
        wirePanel();

        worker.postDelayed(
                sampleLoop,
                1000
        );

        panelStatus(
                "Startet…"
        );

        return START_STICKY;
    }

    private void wirePanel() {

        new Handler(
                getMainLooper()
        ).postDelayed(() -> {

            FloatingPanel p =
                    OverlayService.panel();

            if (p == null) {
                return;
            }

            p.placeBottomLeft(
                    screenW,
                    screenH
            );

            p.setUserSide(
                    userSide
            );

            p.setListener(
                    new FloatingPanel.Listener() {

                        @Override
                        public void onPauseToggle(
                                boolean isPaused
                        ) {

                            paused = isPaused;

                            if (isPaused) {

                                OverlayService.clearAll();

                                panelStatus(
                                        "Pausiert"
                                );

                            } else {

                                lastEngineFen = "";

                                candidateGrid = null;
                                candidateRepeats = 0;

                                if (worker != null) {

                                    worker.post(
                                            () -> drainReader()
                                    );
                                }

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

                            classifier.reset();

                            resetGameState();

                            OverlayService.clearAll();

                            panelEval("");

                            panelStatus(
                                    "Startstellung zeigen"
                            );
                        }

                        @Override
                        public void onSideChange(
                                Side newSide
                        ) {

                            userSide = newSide;

                            Prefs.setUserSide(
                                    getApplicationContext(),
                                    newSide == Side.BLACK
                                            ? "b"
                                            : "w"
                            );

                            classifier.reset();

                            resetGameState();

                            OverlayService.clearAll();

                            panelEval("");

                            panelStatus(
                                    "Farbe geändert · neu kalibrieren"
                            );
                        }
                    }
            );

        }, 400);
    }

    private void resetGameState() {

        confirmedGrid = null;
        candidateGrid = null;

        candidateRepeats = 0;

        lastEngineFen = "";

        observedStm = Side.WHITE;

        currentArrows.clear();

        lockedBoard = null;
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

        if (busy || reader == null) {
            return;
        }

        Image img;

        try {

            img =
                    reader.acquireLatestImage();

        } catch (Exception e) {

            return;
        }

        if (img == null) {
            return;
        }

        busy = true;

        Bitmap bmp = null;

        try {

            bmp =
                    imageToBitmap(img);

            img.close();
            img = null;

            BoardDetector.Board detectedBoard =
                    BoardDetector.locate(bmp);

            if (detectedBoard == null) {

                lostBoardCount++;

                if (lostBoardCount
                        < LOST_BOARD_THRESHOLD) {

                    if (lostBoardCount == 1) {

                        panelStatus(
                                "Suche Brett…"
                        );
                    }

                    return;
                }

                panelStatus(
                        "Brett nicht erkannt"
                );

                OverlayService.clearAll();

                panelEval("");

                return;
            }

            lostBoardCount = 0;

            BoardDetector.Board board =
                    detectedBoard;

            // Nach Kalibrierung nicht mehr bei jeder
            // Erkennung das Brett verschieben.
            if (classifier.isTrained()
                    && lockedBoard != null) {

                if (boardMovedTooFar(
                        lockedBoard,
                        detectedBoard
                )) {

                    OverlayService.clearArrowsOnly();

                    currentArrows.clear();

                    panelEval("");

                    panelStatus(
                            "Brett verschoben · neu kalibrieren"
                    );

                    return;
                }

                board = lockedBoard;
            }

            OverlayService.setBoardBounds(
                    boardBoundsOnScreen(board)
            );

            if (!engineReady) {

                panelStatus(
                        "Brett erkannt · Engine startet"
                );

                return;
            }

            /*
             * ------------------------------------------------
             * KALIBRIERUNG
             * ------------------------------------------------
             */

            if (!classifier.isTrained()) {

                boolean ok =
                        classifier.tryBootstrap(
                                bmp,
                                board
                        );

                if (!ok) {

                    panelStatus(
                            "Startstellung zeigen"
                    );

                    OverlayService.setBoardFrameColor(
                            0x66FFB300
                    );

                    return;
                }

                Side detectedSide =
                        classifier.bootstrapSide();

                if (detectedSide != userSide) {

                    classifier.reset();

                    panelStatus(
                            "Brett/Farbe stimmt nicht"
                    );

                    OverlayService.setBoardFrameColor(
                            0x66D32F2F
                    );

                    return;
                }

                lockedBoard = board;

                OverlayService.setBoardFrameColor(
                        0x6600C853
                );

                confirmedGrid =
                        startingGridFor(userSide);

                observedStm = Side.WHITE;

                candidateGrid = null;
                candidateRepeats = 0;

                lastEngineFen = "";

                panelStatus(
                        "Kalibriert"
                );

                if (observedStm == userSide) {

                    queryEngineAndRender(
                            board
                    );

                } else {

                    panelStatus(
                            "Gegner am Zug"
                    );
                }

                return;
            }

            /*
             * ------------------------------------------------
             * STELLUNG ERKENNEN
             * ------------------------------------------------
             */

            char[][] grid =
                    classifier.classifyBoard(
                            bmp,
                            board
                    );

            if (grid == null) {
                return;
            }

            grid =
                    repairWithMoveLogic(
                            confirmedGrid,
                            grid
                    );

            /*
             * Keine Veränderung.
             */

            if (gridsEqual(
                    grid,
                    confirmedGrid
            )) {

                candidateGrid = null;
                candidateRepeats = 0;

                classifier.commitConfirmed(
                        confirmedGrid,
                        bmp,
                        board
                );

                if (observedStm == userSide) {

                    if (!lastEngineFen.equals(
                            fenOf(
                                    confirmedGrid,
                                    observedStm
                            )
                    )) {

                        queryEngineAndRender(
                                board
                        );

                    } else {

                        repushCurrentArrows(
                                board
                        );

                        if (!currentArrows.isEmpty()) {

                            panelStatus(
                                    "Bereit"
                            );
                        }
                    }

                } else {

                    OverlayService.clearArrowsOnly();

                    currentArrows.clear();

                    panelEval("");

                    panelStatus(
                            "Gegner am Zug"
                    );
                }

                return;
            }

            /*
             * Neue Stellung muss mindestens
             * zweimal identisch erkannt werden.
             */

            if (!gridsEqual(
                    grid,
                    candidateGrid
            )) {

                candidateGrid =
                        copyGrid(grid);

                candidateRepeats = 1;

                panelStatus(
                        "Prüfe Stellung…"
                );

                return;
            }

            candidateRepeats++;

            if (candidateRepeats < 2) {

                panelStatus(
                        "Prüfe Stellung…"
                );

                return;
            }

            /*
             * ------------------------------------------------
             * ZUG ANALYSIEREN
             * ------------------------------------------------
             */

            MoveAnalyzer.Result move =
                    MoveAnalyzer.analyze(
                            confirmedGrid,
                            grid
                    );

            Log.i(
                    TAG,
                    "Move attempt: kind="
                            + move.kind
                            + " mover="
                            + move.mover
                            + " changes="
                            + move.changeCount
            );

            /*
             * Ganz wichtig:
             * Bei einer unklaren Stellung NICHT mehr
             * wie vorher zwangsweise resynchronisieren.
             *
             * Dadurch bekommt Stockfish keine kaputte
             * Stellung mehr mit z.B. 16 falschen Feldern.
             */

            if (move.kind
                    == MoveAnalyzer.Kind.UNKNOWN
                    || move.mover == null) {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                lastEngineFen = "";

                panelEval("");

                panelStatus(
                        "Position nicht sicher"
                );

                return;
            }

            /*
             * Es darf nur die Farbe ziehen,
             * die laut bisherigem Spielverlauf
             * tatsächlich am Zug ist.
             */

            if (move.mover != observedStm) {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                panelEval("");

                lastEngineFen = "";

                panelStatus(
                        "Zugfolge nicht sicher"
                );

                return;
            }

            /*
             * Grundlegende Positionsprüfung.
             */

            char[][] canonical =
                    classifier.bootstrapSide()
                            == Side.BLACK
                            ? FenBuilder.flip(grid)
                            : grid;

            FenBuilder.Validation validation =
                    FenBuilder.validate(
                            canonical
                    );

            if (!validation.ok) {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                panelEval("");

                lastEngineFen = "";

                panelStatus(
                        "Position ungültig"
                );

                return;
            }

            /*
             * Stellung erst JETZT übernehmen.
             */

            confirmedGrid =
                    copyGrid(grid);

            classifier.commitConfirmed(
                    confirmedGrid,
                    bmp,
                    board
            );

            observedStm =
                    move.mover == Side.WHITE
                            ? Side.BLACK
                            : Side.WHITE;

            candidateGrid = null;
            candidateRepeats = 0;

            lastEngineFen = "";

            /*
             * Wenn jetzt wir am Zug sind:
             * Engine berechnen.
             */

            if (observedStm == userSide) {

                queryEngineAndRender(
                        board
                );

            } else {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                panelEval("");

                panelStatus(
                        "Gegner am Zug"
                );
            }

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "loop iter failed: "
                            + e.getMessage(),
                    e
            );

        } finally {

            if (bmp != null
                    && !bmp.isRecycled()) {

                bmp.recycle();
            }

            if (img != null) {

                try {
                    img.close();
                } catch (Exception ignored) {
                }
            }

            busy = false;
        }
    }

    /*
     * ------------------------------------------------
     * ENGINE
     * ------------------------------------------------
     */

    private void queryEngineAndRender(
            BoardDetector.Board board
    ) {

        if (confirmedGrid == null) {
            return;
        }

        char[][] canonical =
                classifier.bootstrapSide()
                        == Side.BLACK
                        ? FenBuilder.flip(
                                confirmedGrid
                        )
                        : confirmedGrid;

        FenBuilder.Validation validation =
                FenBuilder.validate(
                        canonical
                );

        if (!validation.ok) {

            OverlayService.clearArrowsOnly();

            currentArrows.clear();

            panelEval("");

            panelStatus(
                    "Position ungültig"
            );

            return;
        }

        String fen =
                FenBuilder.build(
                        confirmedGrid,
                        classifier.bootstrapSide(),
                        observedStm
                );

        if (fen == null) {

            panelStatus(
                    "Position konnte nicht gelesen werden"
            );

            return;
        }

        if (fen.equals(lastEngineFen)
                && currentArrows != null
                && !currentArrows.isEmpty()) {

            repushCurrentArrows(
                    board
            );

            return;
        }

        int think =
                Prefs.thinkMs(
                        getApplicationContext()
                );

        panelStatus(
                "Berechne…"
        );

        try {

            /*
             * NUR EINE Variante.
             */
            List<StockfishEngine.Pv> pvs =
                    engine.topMoves(
                            fen,
                            think,
                            1
                    );

            if (pvs == null
                    || pvs.isEmpty()) {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                panelEval("");

                panelStatus(
                        "Kein legaler Zug"
                );

                return;
            }

            StockfishEngine.Pv best =
                    pvs.get(0);

            /*
             * Zusätzliche Schutzprüfung:
             * Der von Stockfish gelieferte Zug
             * muss zur tatsächlich intern
             * erkannten Stellung passen.
             */

            if (!bestMoveLooksPlausible(
                    best.uci
            )) {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                panelEval("");

                lastEngineFen = "";

                panelStatus(
                        "Position nicht sicher"
                );

                Log.w(
                        TAG,
                        "Rejected engine move: "
                                + best.uci
                );

                return;
            }

            currentArrows =
                    buildBestArrow(
                            best.uci,
                            board
                    );

            if (currentArrows.isEmpty()) {

                panelStatus(
                        "Zug konnte nicht angezeigt werden"
                );

                return;
            }

            lastEngineFen = fen;

            OverlayService.pushArrows(
                    currentArrows,
                    boardBoundsOnScreen(board)
            );

            panelEval(
                    "BESTER ZUG: "
                            + formatUci(
                                    best.uci
                            )
            );

            panelStatus(
                    "Bereit"
            );

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "engine query failed",
                    e
            );

            OverlayService.clearArrowsOnly();

            currentArrows.clear();

            panelEval("");

            lastEngineFen = "";

            panelStatus(
                    "Engine-Fehler"
            );
        }
    }

    /*
     * ------------------------------------------------
     * NUR EIN GRÜNER PFEIL
     * ------------------------------------------------
     */

    private List<ArrowOverlayView.Arrow>
    buildBestArrow(
            String uci,
            BoardDetector.Board board
    ) {

        List<ArrowOverlayView.Arrow> out =
                new ArrayList<>();

        if (uci == null
                || uci.length() < 4) {

            return out;
        }

        int fx =
                uci.charAt(0) - 'a';

        int fy =
                8 - (
                        uci.charAt(1) - '0'
                );

        int tx =
                uci.charAt(2) - 'a';

        int ty =
                8 - (
                        uci.charAt(3) - '0'
                );

        if (classifier.bootstrapSide()
                == Side.BLACK) {

            fx = 7 - fx;
            fy = 7 - fy;

            tx = 7 - tx;
            ty = 7 - ty;
        }

        float cellX =
                board.square
                        * capToScreenX;

        float cellY =
                board.square
                        * capToScreenY;

        float ox =
                board.left
                        * capToScreenX;

        float oy =
                board.top
                        * capToScreenY;

        float sx =
                ox
                        + (
                        fx + 0.5f
                ) * cellX;

        float sy =
                oy
                        + (
                        fy + 0.5f
                ) * cellY;

        float ex =
                ox
                        + (
                        tx + 0.5f
                ) * cellX;

        float ey =
                oy
                        + (
                        ty + 0.5f
                ) * cellY;

        float cellAvg =
                (
                        cellX + cellY
                ) * 0.5f;

        float width =
                Math.max(
                        12f,
                        BEST_ARROW_WIDTH
                                * Math.min(
                                1.5f,
                                cellAvg / 60f
                        )
                );

        /*
         * Kein Text mitten auf dem Brett.
         * Nur der grüne Pfeil.
         */
        out.add(
                new ArrowOverlayView.Arrow(
                        sx,
                        sy,
                        ex,
                        ey,
                        BEST_ARROW_COLOR,
                        width,
                        null
                )
        );

        return out;
    }

    private void repushCurrentArrows(
            BoardDetector.Board board
    ) {

        if (currentArrows == null
                || currentArrows.isEmpty()) {

            return;
        }

        OverlayService.pushArrows(
                currentArrows,
                boardBoundsOnScreen(board)
        );
    }

    /*
     * ------------------------------------------------
     * SANITY CHECK FÜR ENGINE-ZUG
     * ------------------------------------------------
     */

    private boolean bestMoveLooksPlausible(
            String uci
    ) {

        if (uci == null
                || uci.length() < 4
                || confirmedGrid == null) {

            return false;
        }

        int fromFile =
                uci.charAt(0) - 'a';

        int fromRank =
                uci.charAt(1) - '1';

        int toFile =
                uci.charAt(2) - 'a';

        int toRank =
                uci.charAt(3) - '1';

        if (!inside(
                fromFile,
                fromRank
        )
                || !inside(
                toFile,
                toRank
        )) {

            return false;
        }

        char piece =
                pieceAtCanonical(
                        fromFile,
                        fromRank
                );

        char target =
                pieceAtCanonical(
                        toFile,
                        toRank
                );

        if (piece == '.'
                || piece == 0) {

            return false;
        }

        boolean pieceWhite =
                Character.isUpperCase(
                        piece
                );

        if (userSide == Side.WHITE
                && !pieceWhite) {

            return false;
        }

        if (userSide == Side.BLACK
                && pieceWhite) {

            return false;
        }

        /*
         * Eigenes Stück auf Zielfeld?
         */
        if (target != '.'
                && target != 0) {

            if (Character.isUpperCase(target)
                    == pieceWhite) {

                return false;
            }
        }

        int df =
                toFile - fromFile;

        int dr =
                toRank - fromRank;

        int adf =
                Math.abs(df);

        int adr =
                Math.abs(dr);

        char type =
                Character.toLowerCase(
                        piece
                );

        switch (type) {

            case 'p':
                return plausiblePawnMove(
                        fromFile,
                        fromRank,
                        toFile,
                        toRank,
                        pieceWhite
                );

            case 'n':
                return (
                        adf == 1
                                && adr == 2
                )
                        || (
                        adf == 2
                                && adr == 1
                );

            case 'b':

                if (adf != adr
                        || adf == 0) {

                    return false;
                }

                return pathClear(
                        fromFile,
                        fromRank,
                        toFile,
                        toRank
                );

            case 'r':

                if (!(
                        df == 0
                                ^ dr == 0
                )) {

                    return false;
                }

                return pathClear(
                        fromFile,
                        fromRank,
                        toFile,
                        toRank
                );

            case 'q':

                boolean diagonal =
                        adf == adr
                                && adf > 0;

                boolean straight =
                        (
                                df == 0
                                        ^ dr == 0
                        );

                if (!diagonal
                        && !straight) {

                    return false;
                }

                return pathClear(
                        fromFile,
                        fromRank,
                        toFile,
                        toRank
                );

            case 'k':

                if (adf <= 1
                        && adr <= 1
                        && (
                        adf + adr
                                > 0
                )) {

                    return true;
                }

                /*
                 * Rochade:
                 * e1 -> g1 / c1
                 * e8 -> g8 / c8
                 */
                if (adr == 0
                        && adf == 2) {

                    return plausibleCastle(
                            fromFile,
                            fromRank,
                            toFile,
                            toRank,
                            pieceWhite
                    );
                }

                return false;

            default:
                return false;
        }
    }

    private boolean plausiblePawnMove(
            int fromFile,
            int fromRank,
            int toFile,
            int toRank,
            boolean white
    ) {

        int dir =
                white
                        ? 1
                        : -1;

        int startRank =
                white
                        ? 1
                        : 6;

        int df =
                toFile - fromFile;

        int dr =
                toRank - fromRank;

        char target =
                pieceAtCanonical(
                        toFile,
                        toRank
                );

        /*
         * Ein Feld vor.
         */
        if (df == 0
                && dr == dir) {

            return target == '.'
                    || target == 0;
        }

        /*
         * Zwei Felder aus Ausgangsstellung.
         */
        if (df == 0
                && dr == 2 * dir
                && fromRank == startRank) {

            int middleRank =
                    fromRank + dir;

            char middle =
                    pieceAtCanonical(
                            fromFile,
                            middleRank
                    );

            return (
                    target == '.'
                            || target == 0
            )
                    && (
                    middle == '.'
                            || middle == 0
            );
        }

        /*
         * Normales Schlagen.
         */
        if (Math.abs(df) == 1
                && dr == dir) {

            if (target == '.'
                    || target == 0) {

                /*
                 * En passant können wir hier
                 * ohne vollständige Zug-Historie
                 * nicht sicher bestätigen.
                 */
                return false;
            }

            return Character.isUpperCase(target)
                    != white;
        }

        return false;
    }

    private boolean plausibleCastle(
            int fromFile,
            int fromRank,
            int toFile,
            int toRank,
            boolean white
    ) {

        int expectedRank =
                white
                        ? 0
                        : 7;

        if (fromFile != 4
                || fromRank != expectedRank
                || toRank != expectedRank) {

            return false;
        }

        /*
         * Kurze Rochade.
         */
        if (toFile == 6) {

            char rook =
                    pieceAtCanonical(
                            7,
                            expectedRank
                    );

            if (Character.toLowerCase(rook)
                    != 'r') {

                return false;
            }

            if (Character.isUpperCase(rook)
                    != white) {

                return false;
            }

            return isEmptyCanonical(
                    5,
                    expectedRank
            )
                    && isEmptyCanonical(
                    6,
                    expectedRank
            );
        }

        /*
         * Lange Rochade.
         */
        if (toFile == 2) {

            char rook =
                    pieceAtCanonical(
                            0,
                            expectedRank
                    );

            if (Character.toLowerCase(rook)
                    != 'r') {

                return false;
            }

            if (Character.isUpperCase(rook)
                    != white) {

                return false;
            }

            return isEmptyCanonical(
                    1,
                    expectedRank
            )
                    && isEmptyCanonical(
                    2,
                    expectedRank
            )
                    && isEmptyCanonical(
                    3,
                    expectedRank
            );
        }

        return false;
    }

    private boolean pathClear(
            int fromFile,
            int fromRank,
            int toFile,
            int toRank
    ) {

        int stepFile =
                Integer.compare(
                        toFile,
                        fromFile
                );

        int stepRank =
                Integer.compare(
                        toRank,
                        fromRank
                );

        int f =
                fromFile + stepFile;

        int r =
                fromRank + stepRank;

        while (f != toFile
                || r != toRank) {

            if (!isEmptyCanonical(
                    f,
                    r
            )) {

                return false;
            }

            f += stepFile;
            r += stepRank;
        }

        return true;
    }

    private char pieceAtCanonical(
            int file,
            int rank
    ) {

        if (!inside(
                file,
                rank
        )) {

            return '.';
        }

        /*
         * rank:
         * 0 = erste Reihe
         * 7 = achte Reihe
         *
         * Array:
         * 0 = obere Bildschirmreihe
         */

        int row =
                7 - rank;

        int col =
                file;

        if (classifier.bootstrapSide()
                == Side.BLACK) {

            row =
                    7 - row;

            col =
                    7 - col;
        }

        char c =
                confirmedGrid[row][col];

        return c == 0
                ? '.'
                : c;
    }

    private boolean isEmptyCanonical(
            int file,
            int rank
    ) {

        char c =
                pieceAtCanonical(
                        file,
                        rank
                );

        return c == '.'
                || c == 0;
    }

    private boolean inside(
            int file,
            int rank
    ) {

        return file >= 0
                && file < 8
                && rank >= 0
                && rank < 8;
    }

    /*
     * ------------------------------------------------
     * ERKENNUNGS-REPARATUR
     * ------------------------------------------------
     */

    private char[][] repairWithMoveLogic(
            char[][] prev,
            char[][] curr
    ) {

        if (prev == null
                || curr == null) {

            return curr;
        }

        char emptiedPiece = 0;

        int emptiedCount = 0;
        int filledCount = 0;

        int[] filledPos = null;

        for (int r = 0; r < 8; r++) {

            for (int f = 0; f < 8; f++) {

                char p =
                        prev[r][f] == 0
                                ? '.'
                                : prev[r][f];

                char c =
                        curr[r][f] == 0
                                ? '.'
                                : curr[r][f];

                if (p == c) {
                    continue;
                }

                if (p != '.'
                        && c == '.') {

                    if (emptiedCount == 0) {
                        emptiedPiece = p;
                    }

                    emptiedCount++;

                } else if (
                        p == '.'
                                && c != '.'
                ) {

                    filledCount++;

                    filledPos =
                            new int[]{
                                    r,
                                    f
                            };
                }
            }
        }

        /*
         * Wenn eine Figur von einem Feld auf
         * genau ein anderes Feld zieht, aber
         * der Bildklassifikator den Figurentyp
         * am Ziel verwechselt, behalten wir
         * den bekannten Figurentyp bei.
         */

        if (emptiedCount == 1
                && filledCount == 1
                && filledPos != null) {

            char arrived =
                    curr[
                            filledPos[0]
                    ][
                            filledPos[1]
                    ];

            if (
                    Character.isUpperCase(arrived)
                            == Character.isUpperCase(
                            emptiedPiece
                    )
                            && arrived
                            != emptiedPiece
            ) {

                boolean promotion =
                        Character.toLowerCase(
                                emptiedPiece
                        ) == 'p'
                                && (
                                filledPos[0] == 0
                                        || filledPos[0] == 7
                        );

                if (!promotion) {

                    curr[
                            filledPos[0]
                    ][
                            filledPos[1]
                    ] = emptiedPiece;
                }
            }
        }

        return curr;
    }

    /*
     * ------------------------------------------------
     * BRETT STABILISIEREN
     * ------------------------------------------------
     */

    private boolean boardMovedTooFar(
            BoardDetector.Board locked,
            BoardDetector.Board detected
    ) {

        if (locked == null
                || detected == null) {

            return true;
        }

        float tolerance =
                Math.max(
                        6f,
                        locked.square * 0.35f
                );

        return Math.abs(
                locked.left - detected.left
        ) > tolerance
                || Math.abs(
                locked.top - detected.top
        ) > tolerance
                || Math.abs(
                locked.side - detected.side
        ) > tolerance * 2f;
    }

    /*
     * ------------------------------------------------
     * HILFSMETHODEN
     * ------------------------------------------------
     */

    private String fenOf(
            char[][] grid,
            Side sideToMove
    ) {

        if (grid == null) {
            return "";
        }

        String f =
                FenBuilder.build(
                        grid,
                        classifier.bootstrapSide(),
                        sideToMove
                );

        return f == null
                ? ""
                : f;
    }

    private boolean gridsEqual(
            char[][] a,
            char[][] b
    ) {

        if (a == null
                || b == null) {

            return false;
        }

        for (int r = 0; r < 8; r++) {

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

        if (source == null) {
            return null;
        }

        char[][] copy =
                new char[8][8];

        for (int r = 0; r < 8; r++) {

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
            Side userSide
    ) {

        char[][] base = {

                {
                        'r', 'n', 'b', 'q',
                        'k', 'b', 'n', 'r'
                },

                {
                        'p', 'p', 'p', 'p',
                        'p', 'p', 'p', 'p'
                },

                {
                        '.', '.', '.', '.',
                        '.', '.', '.', '.'
                },

                {
                        '.', '.', '.', '.',
                        '.', '.', '.', '.'
                },

                {
                        '.', '.', '.', '.',
                        '.', '.', '.', '.'
                },

                {
                        '.', '.', '.', '.',
                        '.', '.', '.', '.'
                },

                {
                        'P', 'P', 'P', 'P',
                        'P', 'P', 'P', 'P'
                },

                {
                        'R', 'N', 'B', 'Q',
                        'K', 'B', 'N', 'R'
                }
        };

        if (userSide == Side.BLACK) {

            char[][] flipped =
                    new char[8][8];

            for (int r = 0; r < 8; r++) {

                for (int f = 0; f < 8; f++) {

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

        return base;
    }

    private String formatUci(
            String uci
    ) {

        if (uci == null
                || uci.length() < 4) {

            return "?";
        }

        String text =
                uci.substring(0, 2)
                        + " → "
                        + uci.substring(2, 4);

        if (uci.length() > 4) {

            text +=
                    " = "
                            + Character.toUpperCase(
                            uci.charAt(4)
                    );
        }

        return text;
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

    private void panelStatus(
            String text
    ) {

        FloatingPanel p =
                OverlayService.panel();

        if (p != null) {

            p.setStatus(
                    text
            );
        }
    }

    private void panelEval(
            String text
    ) {

        FloatingPanel p =
                OverlayService.panel();

        if (p != null) {

            p.setEval(
                    text
            );
        }
    }

    /*
     * ------------------------------------------------
     * SCREEN CAPTURE
     * ------------------------------------------------
     */

    private void setupVirtualDisplay() {

        DisplayMetrics dm =
                new DisplayMetrics();

        (
                (WindowManager)
                        getSystemService(
                                WINDOW_SERVICE
                        )
        )
                .getDefaultDisplay()
                .getRealMetrics(dm);

        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

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
                Math.round(
                        screenW * scale
                );

        captureH =
                Math.round(
                        screenH * scale
                );

        density =
                dm.densityDpi;

        capToScreenX =
                (float) screenW
                        / captureW;

        capToScreenY =
                (float) screenH
                        / captureH;

        Log.i(
                TAG,
                "screen="
                        + screenW
                        + "x"
                        + screenH
                        + " capture="
                        + captureW
                        + "x"
                        + captureH
        );

        reader =
                ImageReader.newInstance(
                        captureW,
                        captureH,
                        PixelFormat.RGBA_8888,
                        4
                );

        virtualDisplay =
                projection.createVirtualDisplay(
                        "OpenChepsVD",
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

        int rowStride =
                plane.getRowStride();

        int pixelStride =
                plane.getPixelStride();

        int width =
                image.getWidth();

        int height =
                image.getHeight();

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

        if (bitmapWidth != width) {

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

        return raw;
    }

    /*
     * ------------------------------------------------
     * FOREGROUND SERVICE
     * ------------------------------------------------
     */

    private void startInForeground() {

        NotificationManager nm =
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

            nm.createNotificationChannel(
                    channel
            );
        }

        Intent stopIntent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                ).setAction(
                        ACTION_STOP
                );

        PendingIntent stopPi =
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
                                getString(
                                        R.string.notif_title
                                )
                        )
                        .setContentText(
                                getString(
                                        R.string.notif_text
                                )
                        )
                        .setSmallIcon(
                                R.mipmap.ic_launcher
                        )
                        .setOngoing(true)
                        .addAction(
                                0,
                                "Stop",
                                stopPi
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

        startService(
                new Intent(
                        this,
                        com.opencheps.overlay
                                .OverlayService.class
                )
        );
    }

    @Override
    public void onDestroy() {

        if (worker != null) {

            worker.removeCallbacksAndMessages(
                    null
            );
        }

        try {

            if (engine != null) {
                engine.stop();
            }

        } catch (Exception ignored) {
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (reader != null) {
            reader.close();
        }

        if (projection != null) {
            projection.stop();
        }

        if (workerThread != null) {
            workerThread.quitSafely();
        }

        stopService(
                new Intent(
                        this,
                        com.opencheps.overlay
                                .OverlayService.class
                )
        );

        super.onDestroy();
    }
}
