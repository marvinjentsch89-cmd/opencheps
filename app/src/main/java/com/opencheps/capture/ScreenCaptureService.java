private static final long SAMPLE_PERIOD_MS = 150;

// Diagnose: Den Score anzeigen, ohne eine unkalibrierte 80%-Grenze.
// UNKNOWN und unplausible Ergebnisse werden weiterhin abgelehnt.
private static final double MIN_MOVE_CONFIDENCE = 0.0;


private void sampleOnce(long now) throws Exception {
    candidate.expire(now);

    if (reader == null || classifier == null) {
        return;
    }

    Bitmap bitmap = null;

    try {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) {
                return;
            }

            long timestamp = image.getTimestamp();

            if (timestamp > 0 && timestamp == lastImageTimestamp) {
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
            return;
        }

        if (!candidate.observe(observed, now)) {
            statusText = "Bildänderung erkannt – bestätige…";
            frameColor = COLOR_WAITING;
            return;
        }

        /*
         * Zuerst einen einzelnen Zug prüfen.
         * Der bestätigte Zustand wird nur als Kopie übergeben.
         */
        char[][] proposal = copyGrid(observed);

        MoveAnalyzer.Result move = MoveAnalyzer.analyze(
                copyGrid(confirmedGrid),
                proposal
        );

        if (validMoveResult(move, confirmedGrid, proposal)
                && gridsEqual(proposal, observed)) {

            acceptMoves(
                    new MoveSnapshot(move),
                    null,
                    proposal,
                    observed,
                    bitmap,
                    board
            );

            return;
        }

        /*
         * Zwei aufeinanderfolgende Züge prüfen.
         *
         * Beispiel:
         * Startstellung -> d2-d4 -> d7-d5
         *
         * Beide Übergänge müssen vom Analyzer bestätigt werden.
         * Die Endstellung muss exakt zum beobachteten Raster passen.
         */
        MovePair pair = recoverTwoMoves(
                confirmedGrid,
                observed,
                expectedSide
        );

        if (pair != null) {
            acceptMoves(
                    pair.first,
                    pair.second,
                    observed,
                    observed,
                    bitmap,
                    board
            );

            return;
        }

        /*
         * Erneut auswerten, weil der Analyzer Result-Objekte
         * intern möglicherweise wiederverwendet.
         */
        proposal = copyGrid(observed);

        move = MoveAnalyzer.analyze(
                copyGrid(confirmedGrid),
                proposal
        );

        /*
         * Kleine Bildfehler darf der Analyzer wie bisher reparieren.
         * Bei vier oder mehr Änderungen keine unklare Mehrzugfolge
         * durch eine reparierte Einzelstellung ersetzen.
         */
        if (validMoveResult(move, confirmedGrid, proposal)
                && countChanges(confirmedGrid, observed) < 4) {

            acceptMoves(
                    new MoveSnapshot(move),
                    null,
                    proposal,
                    observed,
                    bitmap,
                    board
            );

            return;
        }

        statusText = "NICHT SICHER";
        frameColor = COLOR_WAITING;

        evalText =
                "ÄNDERUNGEN: "
                        + countChanges(confirmedGrid, observed)
                        + " | ERWARTET: "
                        + sideGerman(expectedSide)
                        + "\nANALYSE: "
                        + (move == null
                        ? "kein Ergebnis"
                        : String.valueOf(move.kind))
                        + " | SCORE: "
                        + scoreText(move)
                        + "\nGRUND: "
                        + rejectionReason(
                                move,
                                confirmedGrid,
                                proposal
                        );

    } finally {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}


private void acceptMoves(
        MoveSnapshot first,
        @Nullable MoveSnapshot second,
        char[][] after,
        char[][] observed,
        Bitmap bitmap,
        BoardDetector.Board board
) throws Exception {
    if (stopping) {
        return;
    }

    int changes = countChanges(confirmedGrid, observed);

    boolean recoveredSync =
            second != null || first.mover != expectedSide;

    /*
     * Nur exakt passende Bilddaten zum Nachtrainieren verwenden.
     */
    if (gridsEqual(after, observed)) {
        try {
            classifier.commitConfirmed(
                    copyGrid(after),
                    bitmap,
                    board
            );

        } catch (Exception error) {
            resetCalibration(
                    "Kalibrierung fehlgeschlagen – Startstellung zeigen"
            );
            throw error;
        }
    }

    confirmedGrid = copyGrid(after);

    expectedSide = opposite(
            second == null ? first.mover : second.mover
    );

    candidate.reset();
    frameColor = COLOR_READY;

    String firstText = describeMove(first);

    if (second == null) {
        lastMoveText = firstText;

        statusText = recoveredSync
                ? "SYNC KORRIGIERT: " + firstText
                : "ERKANNT: " + firstText;

        evalText =
                "ERKANNT: "
                        + firstText
                        + "\nDANACH: "
                        + sideGerman(expectedSide)
                        + " am Zug"
                        + "\nÄNDERUNGEN: "
                        + changes
                        + " | SCORE: "
                        + Math.round(first.confidence * 100)
                        + "%"
                        + "\nART: "
                        + kindGerman(first.kind);

    } else {
        String secondText = describeMove(second);

        lastMoveText =
                firstText
                        + " | "
                        + secondText
                        + " (rekonstruiert)";

        statusText = "2 ZÜGE REKONSTRUIERT";

        evalText =
                firstText
                        + "\n"
                        + secondText
                        + "\nDANACH: "
                        + sideGerman(expectedSide)
                        + " am Zug"
                        + "\nSCORES: "
                        + Math.round(first.confidence * 100)
                        + "% / "
                        + Math.round(second.confidence * 100)
                        + "%";
    }

    confirmedEvalText = evalText;

    Log.i(
            TAG,
            "MOVE "
                    + lastMoveText
                    + " syncRecovered="
                    + recoveredSync
    );
}


private String describeMove(MoveSnapshot move) {
    return sideGerman(move.mover)
            + " "
            + squareNameFor(
                    move.fromRow,
                    move.fromFile,
                    boardPerspective
            )
            + "→"
            + squareNameFor(
                    move.toRow,
                    move.toFile,
                    boardPerspective
            );
}


/*
 * Begrenzte Wiederherstellung zweier aufeinanderfolgender Züge.
 *
 * Die Reihenfolge folgt expectedSide. Sie wird rekonstruiert,
 * nicht aus einem tatsächlich beobachteten Zwischenbild gelesen.
 *
 * Es werden ausschließlich eindeutig aufteilbare Änderungen geprüft.
 * Beide Einzelzüge müssen vom vorhandenen MoveAnalyzer bestätigt
 * werden und die beobachtete Endstellung exakt ergeben.
 *
 * Überlappende Züge, mehr als sechs geänderte Felder und mehrere
 * mögliche Zwischenstellungen werden nicht geraten.
 *
 * Die vollständige Schach-Legalitätsprüfung bleibt Aufgabe
 * von MoveAnalyzer.
 */
private static MovePair recoverTwoMoves(
        char[][] before,
        char[][] observed,
        Side firstSide
) throws Exception {
    if (!isGridValid(before)
            || !isGridValid(observed)
            || !hasBothKings(before)
            || !hasBothKings(observed)
            || (firstSide != Side.WHITE
            && firstSide != Side.BLACK)) {
        return null;
    }

    int changes = countChanges(before, observed);

    if (changes < 4 || changes > 6) {
        return null;
    }

    int[] squares = new int[changes];
    int index = 0;

    for (int row = 0; row < 8; row++) {
        for (int file = 0; file < 8; file++) {
            if (before[row][file] != observed[row][file]) {
                squares[index++] = row * 8 + file;
            }
        }
    }

    MovePair found = null;

    for (int mask = 1; mask < (1 << changes) - 1; mask++) {
        int firstChanges = Integer.bitCount(mask);
        int secondChanges = changes - firstChanges;

        if (firstChanges < 2
                || firstChanges > 4
                || secondChanges < 2
                || secondChanges > 4) {
            continue;
        }

        char[][] middle = copyGrid(before);

        boolean departure = false;
        boolean arrival = false;

        for (int bit = 0; bit < changes; bit++) {
            if ((mask & (1 << bit)) == 0) {
                continue;
            }

            int row = squares[bit] / 8;
            int file = squares[bit] % 8;

            middle[row][file] = observed[row][file];

            departure |=
                    belongsTo(before[row][file], firstSide)
                            && middle[row][file] == '.';

            arrival |=
                    belongsTo(middle[row][file], firstSide);
        }

        if (!departure
                || !arrival
                || !hasBothKings(middle)) {
            continue;
        }

        char[][] firstAfter = copyGrid(middle);

        MoveAnalyzer.Result firstResult =
                MoveAnalyzer.analyze(
                        copyGrid(before),
                        firstAfter
                );

        if (!validMoveResult(firstResult, before, firstAfter)
                || firstResult.mover != firstSide
                || !gridsEqual(firstAfter, middle)) {
            continue;
        }

        /*
         * Werte kopieren, bevor der Analyzer erneut aufgerufen wird.
         */
        MoveSnapshot first = new MoveSnapshot(firstResult);

        char[][] secondAfter = copyGrid(observed);

        MoveAnalyzer.Result secondResult =
                MoveAnalyzer.analyze(
                        copyGrid(middle),
                        secondAfter
                );

        if (!validMoveResult(secondResult, middle, secondAfter)
                || secondResult.mover != opposite(firstSide)
                || !gridsEqual(secondAfter, observed)) {
            continue;
        }

        if (found != null) {
            /*
             * Mehrere mögliche Verläufe:
             * Die bisher bestätigte Stellung behalten.
             */
            return null;
        }

        found = new MovePair(
                first,
                new MoveSnapshot(secondResult)
        );
    }

    return found;
}


private static final class MoveSnapshot {

    final MoveAnalyzer.Kind kind;
    final Side mover;

    final int fromRow;
    final int fromFile;
    final int toRow;
    final int toFile;

    final double confidence;

    MoveSnapshot(MoveAnalyzer.Result result) {
        kind = result.kind;
        mover = result.mover;

        fromRow = result.fromRow;
        fromFile = result.fromFile;
        toRow = result.toRow;
        toFile = result.toFile;

        confidence = result.confidence;
    }
}


private static final class MovePair {

    final MoveSnapshot first;
    final MoveSnapshot second;

    MovePair(MoveSnapshot first, MoveSnapshot second) {
        this.first = first;
        this.second = second;
    }
}


private static String scoreText(
        @Nullable MoveAnalyzer.Result move
) {
    if (move == null || !finite(move.confidence)) {
        return "ungültig";
    }

    return Math.round(move.confidence * 100) + "%";
}


private static String rejectionReason(
        @Nullable MoveAnalyzer.Result move,
        char[][] before,
        char[][] after
) {
    if (move == null) {
        return "Analyzer liefert kein Ergebnis";
    }

    if (move.kind == null
            || move.kind == MoveAnalyzer.Kind.UNKNOWN) {
        return "Kein eindeutiger Verlauf erkannt";
    }

    if (move.kind == MoveAnalyzer.Kind.NONE) {
        return "Analyzer meldet keinen Zug";
    }

    if (!finite(move.confidence)
            || move.confidence < MIN_MOVE_CONFIDENCE
            || move.confidence > 1.0) {
        return "Ungültiger oder zu kleiner Score";
    }

    if (!isGridValid(before) || !isGridValid(after)) {
        return "Ungültiges Figurenraster";
    }

    if (!hasBothKings(before) || !hasBothKings(after)) {
        return "Könige nicht eindeutig erkannt";
    }

    if (move.mover != Side.WHITE && move.mover != Side.BLACK) {
        return "Zugfarbe fehlt";
    }

    if (!validSquare(move.fromRow, move.fromFile)
            || !validSquare(move.toRow, move.toFile)) {
        return "Ungültige Zugkoordinaten";
    }

    return "Zug passt nicht zur erkannten Stellung";
}
