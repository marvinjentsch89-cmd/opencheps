package com.opencheps.vision;

import com.opencheps.util.Side;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MoveAnalyzer {

    public enum Kind {
        NONE,
        QUIET,
        CAPTURE,
        CASTLE,
        EN_PASSANT,
        PROMOTION,
        UNKNOWN
    }

    public static class Result {
        public final Kind kind;
        public final Side mover;
        public final int changeCount;

        public final int fromRow;
        public final int fromFile;
        public final int toRow;
        public final int toFile;

        public final double confidence;

        public Result(
                Kind kind,
                Side mover,
                int changeCount
        ) {
            this(
                    kind,
                    mover,
                    changeCount,
                    -1,
                    -1,
                    -1,
                    -1,
                    0.0
            );
        }

        public Result(
                Kind kind,
                Side mover,
                int changeCount,
                int fromRow,
                int fromFile,
                int toRow,
                int toFile,
                double confidence
        ) {
            this.kind = kind;
            this.mover = mover;
            this.changeCount = changeCount;

            this.fromRow = fromRow;
            this.fromFile = fromFile;
            this.toRow = toRow;
            this.toFile = toFile;

            this.confidence = confidence;
        }
    }

    private static class Candidate {

        char[][] grid;

        Kind kind;
        Side mover;

        int fromRow;
        int fromFile;
        int toRow;
        int toFile;

        double score;

        Candidate(
                char[][] grid,
                Kind kind,
                Side mover,
                int fromRow,
                int fromFile,
                int toRow,
                int toFile
        ) {
            this.grid = grid;
            this.kind = kind;
            this.mover = mover;

            this.fromRow = fromRow;
            this.fromFile = fromFile;
            this.toRow = toRow;
            this.toFile = toFile;
        }
    }

    /*
     * ============================================================
     * HAUPTFUNKTION
     * ============================================================
     *
     * prev:
     * letzte bestätigte Stellung
     *
     * observed:
     * aktuell vom Bildklassifikator erkannte Stellung
     *
     * Die Funktion sucht einen legalen Zug aus prev,
     * der am besten zu observed passt.
     *
     * Bei Erfolg wird observed IN PLACE durch die
     * berechnete saubere Folgestellung ersetzt.
     *
     * Dadurch übernimmt der Rest der App nicht mehr
     * 10–20 falsch erkannte Figuren.
     * ============================================================
     */

    public static Result analyze(
            char[][] prev,
            char[][] observed
    ) {

        if (!validGrid(prev)
                || !validGrid(observed)) {

            return new Result(
                    Kind.UNKNOWN,
                    null,
                    0
            );
        }

        int rawChanges =
                countRawChanges(
                        prev,
                        observed
                );

        if (rawChanges == 0) {

            return new Result(
                    Kind.NONE,
                    null,
                    0
            );
        }

        /*
         * Je nach Brettausrichtung bewegen sich
         * weiße Bauern im Array nach oben oder unten.
         */
        int whiteForward =
                inferWhiteForward(prev);

        List<Candidate> candidates =
                new ArrayList<>();

        generateCandidates(
                prev,
                Side.WHITE,
                whiteForward,
                candidates
        );

        generateCandidates(
                prev,
                Side.BLACK,
                whiteForward,
                candidates
        );

        if (candidates.isEmpty()) {

            return new Result(
                    Kind.UNKNOWN,
                    null,
                    rawChanges
            );
        }

        Candidate best = null;
        Candidate second = null;

        for (Candidate candidate : candidates) {

            candidate.score =
                    scoreCandidate(
                            prev,
                            observed,
                            candidate
                    );

            if (best == null
                    || candidate.score
                    < best.score) {

                second = best;
                best = candidate;

            } else if (
                    second == null
                            || candidate.score
                            < second.score
            ) {

                second = candidate;
            }
        }

        if (best == null) {

            return new Result(
                    Kind.UNKNOWN,
                    null,
                    rawChanges
            );
        }

        /*
         * Viele reine Figurentyp-Fehler dürfen vorkommen.
         *
         * Beispiel:
         * Springer wird wegen Highlight kurz als Läufer
         * klassifiziert.
         *
         * Belegungs-/Farbfehler werden deutlich stärker
         * bestraft und führen deshalb trotzdem zur Ablehnung.
         */
        double maxAcceptedScore =
                4.5
                        + Math.max(
                        0,
                        rawChanges - 2
                ) * 0.25;

        if (best.score
                > maxAcceptedScore) {

            return new Result(
                    Kind.UNKNOWN,
                    null,
                    rawChanges
            );
        }

        int evidence =
                moveEvidence(
                        observed,
                        best
                );

        /*
         * Sind zwei Züge nahezu gleich wahrscheinlich
         * und sehen wir Start/Ziel nicht deutlich,
         * lieber nicht raten.
         */
        if (second != null) {

            double gap =
                    second.score
                            - best.score;

            if (gap < 0.35
                    && evidence < 2) {

                return new Result(
                        Kind.UNKNOWN,
                        null,
                        rawChanges
                );
            }
        }

        /*
         * Mindestens Start ODER Ziel sollte optisch
         * zum Zug passen.
         */
        if (evidence == 0
                && best.score > 1.5) {

            return new Result(
                    Kind.UNKNOWN,
                    null,
                    rawChanges
            );
        }

        /*
         * GANZ WICHTIG:
         *
         * Die unsichere Bilderkennung wird jetzt
         * durch die saubere, aus dem Zug berechnete
         * Stellung ersetzt.
         */
        overwriteGrid(
                observed,
                best.grid
        );

        double confidence =
                1.0 / (
                        1.0
                                + best.score
                );

        return new Result(
                best.kind,
                best.mover,
                rawChanges,
                best.fromRow,
                best.fromFile,
                best.toRow,
                best.toFile,
                confidence
        );
    }

    /*
     * ============================================================
     * ZUGKANDIDATEN ERZEUGEN
     * ============================================================
     */

    private static void generateCandidates(
            char[][] grid,
            Side side,
            int whiteForward,
            List<Candidate> out
    ) {

        int pawnDir =
                side == Side.WHITE
                        ? whiteForward
                        : -whiteForward;

        for (int r = 0; r < 8; r++) {

            for (int f = 0; f < 8; f++) {

                char piece =
                        norm(
                                grid[r][f]
                        );

                if (piece == '.') {
                    continue;
                }

                if (sideOf(piece)
                        != side) {

                    continue;
                }

                switch (
                        Character.toLowerCase(
                                piece
                        )
                ) {

                    case 'p':

                        generatePawnMoves(
                                grid,
                                side,
                                pawnDir,
                                whiteForward,
                                r,
                                f,
                                out
                        );

                        break;

                    case 'n':

                        generateKnightMoves(
                                grid,
                                side,
                                whiteForward,
                                r,
                                f,
                                out
                        );

                        break;

                    case 'b':

                        generateSlidingMoves(
                                grid,
                                side,
                                whiteForward,
                                r,
                                f,
                                out,
                                new int[][]{
                                        {-1, -1},
                                        {-1, 1},
                                        {1, -1},
                                        {1, 1}
                                }
                        );

                        break;

                    case 'r':

                        generateSlidingMoves(
                                grid,
                                side,
                                whiteForward,
                                r,
                                f,
                                out,
                                new int[][]{
                                        {-1, 0},
                                        {1, 0},
                                        {0, -1},
                                        {0, 1}
                                }
                        );

                        break;

                    case 'q':

                        generateSlidingMoves(
                                grid,
                                side,
                                whiteForward,
                                r,
                                f,
                                out,
                                new int[][]{
                                        {-1, -1},
                                        {-1, 1},
                                        {1, -1},
                                        {1, 1},
                                        {-1, 0},
                                        {1, 0},
                                        {0, -1},
                                        {0, 1}
                                }
                        );

                        break;

                    case 'k':

                        generateKingMoves(
                                grid,
                                side,
                                whiteForward,
                                r,
                                f,
                                out
                        );

                        generateCastles(
                                grid,
                                side,
                                whiteForward,
                                r,
                                f,
                                out
                        );

                        break;
                }
            }
        }
    }

    /*
     * ============================================================
     * BAUER
     * ============================================================
     */

    private static void generatePawnMoves(
            char[][] grid,
            Side side,
            int dir,
            int whiteForward,
            int row,
            int file,
            List<Candidate> out
    ) {

        int oneRow =
                row + dir;

        if (inside(
                oneRow,
                file
        )
                && empty(
                grid[oneRow][file]
        )) {

            addPawnMove(
                    grid,
                    side,
                    whiteForward,
                    row,
                    file,
                    oneRow,
                    file,
                    false,
                    out
            );

            int startRow =
                    dir < 0
                            ? 6
                            : 1;

            int twoRow =
                    row + dir * 2;

            if (row == startRow
                    && inside(
                    twoRow,
                    file
            )
                    && empty(
                    grid[twoRow][file]
            )) {

                addCandidateMove(
                        grid,
                        side,
                        whiteForward,
                        row,
                        file,
                        twoRow,
                        file,
                        Kind.QUIET,
                        out
                );
            }
        }

        for (int df :
                new int[]{
                        -1,
                        1
                }) {

            int targetRow =
                    row + dir;

            int targetFile =
                    file + df;

            if (!inside(
                    targetRow,
                    targetFile
            )) {

                continue;
            }

            char target =
                    norm(
                            grid[
                                    targetRow
                            ][
                                    targetFile
                            ]
                    );

            /*
             * Normales Schlagen.
             */
            if (target != '.'
                    && sideOf(target)
                    != side) {

                addPawnMove(
                        grid,
                        side,
                        whiteForward,
                        row,
                        file,
                        targetRow,
                        targetFile,
                        true,
                        out
                );

                continue;
            }

            /*
             * En passant Kandidat.
             *
             * Die komplette Zug-Historie kennen wir hier
             * nicht. Deshalb erlauben wir ihn nur als
             * visuellen Kandidaten, wenn direkt neben dem
             * Bauern ein gegnerischer Bauer steht.
             */
            if (target == '.') {

                int adjacentRow =
                        row;

                int adjacentFile =
                        file + df;

                if (!inside(
                        adjacentRow,
                        adjacentFile
                )) {

                    continue;
                }

                char adjacent =
                        norm(
                                grid[
                                        adjacentRow
                                ][
                                        adjacentFile
                                ]
                        );

                if (Character.toLowerCase(
                        adjacent
                ) == 'p'
                        && sideOf(adjacent)
                        != side) {

                    char[][] next =
                            copyGrid(grid);

                    char pawn =
                            next[row][file];

                    next[row][file] = '.';

                    next[
                            adjacentRow
                    ][
                            adjacentFile
                    ] = '.';

                    next[
                            targetRow
                    ][
                            targetFile
                    ] = pawn;

                    if (kingSafe(
                            next,
                            side,
                            whiteForward
                    )) {

                        out.add(
                                new Candidate(
                                        next,
                                        Kind.EN_PASSANT,
                                        side,
                                        row,
                                        file,
                                        targetRow,
                                        targetFile
                                )
                        );
                    }
                }
            }
        }
    }

    private static void addPawnMove(
            char[][] grid,
            Side side,
            int whiteForward,
            int fromRow,
            int fromFile,
            int toRow,
            int toFile,
            boolean capture,
            List<Candidate> out
    ) {

        int promotionRow =
                (
                        side == Side.WHITE
                                ? whiteForward
                                : -whiteForward
                ) < 0
                        ? 0
                        : 7;

        if (toRow == promotionRow) {

            char[][] next =
                    copyGrid(grid);

            next[fromRow][fromFile] =
                    '.';

            next[toRow][toFile] =
                    side == Side.WHITE
                            ? 'Q'
                            : 'q';

            if (kingSafe(
                    next,
                    side,
                    whiteForward
            )) {

                out.add(
                        new Candidate(
                                next,
                                Kind.PROMOTION,
                                side,
                                fromRow,
                                fromFile,
                                toRow,
                                toFile
                        )
                );
            }

        } else {

            addCandidateMove(
                    grid,
                    side,
                    whiteForward,
                    fromRow,
                    fromFile,
                    toRow,
                    toFile,
                    capture
                            ? Kind.CAPTURE
                            : Kind.QUIET,
                    out
            );
        }
    }

    /*
     * ============================================================
     * SPRINGER
     * ============================================================
     */

    private static void generateKnightMoves(
            char[][] grid,
            Side side,
            int whiteForward,
            int row,
            int file,
            List<Candidate> out
    ) {

        int[][] jumps = {
                {-2, -1},
                {-2, 1},
                {-1, -2},
                {-1, 2},
                {1, -2},
                {1, 2},
                {2, -1},
                {2, 1}
        };

        for (int[] jump : jumps) {

            int r =
                    row + jump[0];

            int f =
                    file + jump[1];

            if (!inside(r, f)) {
                continue;
            }

            char target =
                    norm(
                            grid[r][f]
                    );

            if (target != '.'
                    && sideOf(target)
                    == side) {

                continue;
            }

            addCandidateMove(
                    grid,
                    side,
                    whiteForward,
                    row,
                    file,
                    r,
                    f,
                    target == '.'
                            ? Kind.QUIET
                            : Kind.CAPTURE,
                    out
            );
        }
    }

    /*
     * ============================================================
     * LÄUFER / TURM / DAME
     * ============================================================
     */

    private static void generateSlidingMoves(
            char[][] grid,
            Side side,
            int whiteForward,
            int row,
            int file,
            List<Candidate> out,
            int[][] directions
    ) {

        for (int[] direction : directions) {

            int r =
                    row + direction[0];

            int f =
                    file + direction[1];

            while (inside(
                    r,
                    f
            )) {

                char target =
                        norm(
                                grid[r][f]
                        );

                if (target == '.') {

                    addCandidateMove(
                            grid,
                            side,
                            whiteForward,
                            row,
                            file,
                            r,
                            f,
                            Kind.QUIET,
                            out
                    );

                } else {

                    if (sideOf(target)
                            != side) {

                        addCandidateMove(
                                grid,
                                side,
                                whiteForward,
                                row,
                                file,
                                r,
                                f,
                                Kind.CAPTURE,
                                out
                        );
                    }

                    break;
                }

                r += direction[0];
                f += direction[1];
            }
        }
    }

    /*
     * ============================================================
     * KÖNIG
     * ============================================================
     */

    private static void generateKingMoves(
            char[][] grid,
            Side side,
            int whiteForward,
            int row,
            int file,
            List<Candidate> out
    ) {

        for (int dr = -1;
             dr <= 1;
             dr++) {

            for (int df = -1;
                 df <= 1;
                 df++) {

                if (dr == 0
                        && df == 0) {

                    continue;
                }

                int r =
                        row + dr;

                int f =
                        file + df;

                if (!inside(
                        r,
                        f
                )) {

                    continue;
                }

                char target =
                        norm(
                                grid[r][f]
                        );

                if (target != '.'
                        && sideOf(target)
                        == side) {

                    continue;
                }

                addCandidateMove(
                        grid,
                        side,
                        whiteForward,
                        row,
                        file,
                        r,
                        f,
                        target == '.'
                                ? Kind.QUIET
                                : Kind.CAPTURE,
                        out
                );
            }
        }
    }

    /*
     * Rochade wird unabhängig davon erkannt,
     * ob das Brett aus Weiß- oder Schwarzsicht
     * dargestellt wird.
     */
    private static void generateCastles(
            char[][] grid,
            Side side,
            int whiteForward,
            int kingRow,
            int kingFile,
            List<Candidate> out
    ) {

        if (isKingInCheck(
                grid,
                side,
                whiteForward
        )) {

            return;
        }

        for (int direction :
                new int[]{
                        -1,
                        1
                }) {

            int f =
                    kingFile + direction;

            int rookFile = -1;

            boolean clear = true;

            while (f >= 0
                    && f < 8) {

                char c =
                        norm(
                                grid[
                                        kingRow
                                ][
                                        f
                                ]
                        );

                if (c == '.') {

                    f += direction;
                    continue;
                }

                if (sideOf(c) == side
                        && Character.toLowerCase(c)
                        == 'r') {

                    rookFile = f;

                } else {

                    clear = false;
                }

                break;
            }

            if (!clear
                    || rookFile < 0) {

                continue;
            }

            if (Math.abs(
                    rookFile
                            - kingFile
            ) < 3) {

                continue;
            }

            int kingTargetFile =
                    kingFile
                            + 2 * direction;

            int rookTargetFile =
                    kingFile
                            + direction;

            if (!inside(
                    kingRow,
                    kingTargetFile
            )) {

                continue;
            }

            /*
             * König darf nicht durch Schach ziehen.
             */
            char[][] middle =
                    copyGrid(grid);

            middle[
                    kingRow
            ][
                    kingFile
            ] = '.';

            middle[
                    kingRow
            ][
                    kingFile + direction
            ] =
                    grid[
                            kingRow
                    ][
                            kingFile
                    ];

            if (!kingSafe(
                    middle,
                    side,
                    whiteForward
            )) {

                continue;
            }

            char[][] next =
                    copyGrid(grid);

            char king =
                    next[
                            kingRow
                    ][
                            kingFile
                    ];

            char rook =
                    next[
                            kingRow
                    ][
                            rookFile
                    ];

            next[
                    kingRow
            ][
                    kingFile
            ] = '.';

            next[
                    kingRow
            ][
                    rookFile
            ] = '.';

            next[
                    kingRow
            ][
                    kingTargetFile
            ] = king;

            next[
                    kingRow
            ][
                    rookTargetFile
            ] = rook;

            if (!kingSafe(
                    next,
                    side,
                    whiteForward
            )) {

                continue;
            }

            out.add(
                    new Candidate(
                            next,
                            Kind.CASTLE,
                            side,
                            kingRow,
                            kingFile,
                            kingRow,
                            kingTargetFile
                    )
            );
        }
    }

    /*
     * ============================================================
     * NORMALEN ZUG HINZUFÜGEN
     * ============================================================
     */

    private static void addCandidateMove(
            char[][] grid,
            Side side,
            int whiteForward,
            int fromRow,
            int fromFile,
            int toRow,
            int toFile,
            Kind kind,
            List<Candidate> out
    ) {

        char[][] next =
                copyGrid(grid);

        char moving =
                next[
                        fromRow
                ][
                        fromFile
                ];

        next[
                fromRow
        ][
                fromFile
        ] = '.';

        next[
                toRow
        ][
                toFile
        ] = moving;

        if (!kingSafe(
                next,
                side,
                whiteForward
        )) {

            return;
        }

        out.add(
                new Candidate(
                        next,
                        kind,
                        side,
                        fromRow,
                        fromFile,
                        toRow,
                        toFile
                )
        );
    }

    /*
     * ============================================================
     * KANDIDAT GEGEN BILDERKENNUNG BEWERTEN
     * ============================================================
     */

    private static double scoreCandidate(
            char[][] prev,
            char[][] observed,
            Candidate candidate
    ) {

        double score = 0.0;

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                char expected =
                        norm(
                                candidate.grid[r][f]
                        );

                char actual =
                        norm(
                                observed[r][f]
                        );

                if (expected == actual) {
                    continue;
                }

                boolean expectedEmpty =
                        expected == '.';

                boolean actualEmpty =
                        actual == '.';

                /*
                 * Figur vorhanden / leer ist die
                 * wichtigste Information.
                 */
                if (expectedEmpty
                        != actualEmpty) {

                    score += 2.20;

                    continue;
                }

                /*
                 * Beide Felder enthalten Figuren.
                 */
                if (!expectedEmpty) {

                    /*
                     * Richtige Farbe, aber falscher
                     * Figurentyp:
                     *
                     * nur kleine Strafe.
                     *
                     * Genau diese Fehler produziert die
                     * Bilderkennung bei Brett-Highlights.
                     */
                    if (sideOf(expected)
                            == sideOf(actual)) {

                        score += 0.18;

                    } else {

                        /*
                         * Falsche Farbe ist wesentlich
                         * verdächtiger.
                         */
                        score += 1.60;
                    }
                }
            }
        }

        /*
         * Das Startfeld sollte nach einem Zug leer sein.
         */
        char observedFrom =
                norm(
                        observed[
                                candidate.fromRow
                        ][
                                candidate.fromFile
                        ]
                );

        if (observedFrom != '.') {

            score += 2.40;
        }

        /*
         * Auf dem Zielfeld sollte die Farbe des
         * ziehenden Spielers stehen.
         */
        char observedTo =
                norm(
                        observed[
                                candidate.toRow
                        ][
                                candidate.toFile
                        ]
                );

        if (observedTo == '.') {

            score += 2.00;

        } else if (
                sideOf(observedTo)
                        != candidate.mover
        ) {

            score += 2.50;
        }

        return score;
    }

    /*
     * Wie klar sieht man Start- und Zielfeld?
     */
    private static int moveEvidence(
            char[][] observed,
            Candidate candidate
    ) {

        int evidence = 0;

        char source =
                norm(
                        observed[
                                candidate.fromRow
                        ][
                                candidate.fromFile
                        ]
                );

        char target =
                norm(
                        observed[
                                candidate.toRow
                        ][
                                candidate.toFile
                        ]
                );

        if (source == '.') {
            evidence++;
        }

        if (target != '.'
                && sideOf(target)
                == candidate.mover) {

            evidence++;
        }

        return evidence;
    }

    /*
     * ============================================================
     * KÖNIGSSICHERHEIT
     * ============================================================
     */

    private static boolean kingSafe(
            char[][] grid,
            Side side,
            int whiteForward
    ) {

        return !isKingInCheck(
                grid,
                side,
                whiteForward
        );
    }

    private static boolean isKingInCheck(
            char[][] grid,
            Side side,
            int whiteForward
    ) {

        char king =
                side == Side.WHITE
                        ? 'K'
                        : 'k';

        int kingRow = -1;
        int kingFile = -1;

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                if (grid[r][f]
                        == king) {

                    kingRow = r;
                    kingFile = f;

                    break;
                }
            }
        }

        /*
         * Ungültige Stellung.
         */
        if (kingRow < 0) {
            return true;
        }

        Side attacker =
                opposite(side);

        return isSquareAttackedBy(
                grid,
                kingRow,
                kingFile,
                attacker,
                whiteForward
        );
    }

    private static boolean isSquareAttackedBy(
            char[][] grid,
            int targetRow,
            int targetFile,
            Side attacker,
            int whiteForward
    ) {

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                char piece =
                        norm(
                                grid[r][f]
                        );

                if (piece == '.'
                        || sideOf(piece)
                        != attacker) {

                    continue;
                }

                int dr =
                        targetRow - r;

                int df =
                        targetFile - f;

                int adr =
                        Math.abs(dr);

                int adf =
                        Math.abs(df);

                switch (
                        Character.toLowerCase(
                                piece
                        )
                ) {

                    case 'p':

                        int dir =
                                attacker
                                        == Side.WHITE
                                        ? whiteForward
                                        : -whiteForward;

                        if (dr == dir
                                && adf == 1) {

                            return true;
                        }

                        break;

                    case 'n':

                        if (
                                (
                                        adr == 2
                                                && adf == 1
                                )
                                        || (
                                        adr == 1
                                                && adf == 2
                                )
                        ) {

                            return true;
                        }

                        break;

                    case 'b':

                        if (adr == adf
                                && adr > 0
                                && pathClear(
                                grid,
                                r,
                                f,
                                targetRow,
                                targetFile
                        )) {

                            return true;
                        }

                        break;

                    case 'r':

                        if (
                                (
                                        dr == 0
                                                || df == 0
                                )
                                        && !(
                                        dr == 0
                                                && df == 0
                                )
                                        && pathClear(
                                        grid,
                                        r,
                                        f,
                                        targetRow,
                                        targetFile
                                )
                        ) {

                            return true;
                        }

                        break;

                    case 'q':

                        boolean diagonal =
                                adr == adf
                                        && adr > 0;

                        boolean straight =
                                (
                                        dr == 0
                                                || df == 0
                                )
                                        && !(
                                        dr == 0
                                                && df == 0
                                );

                        if (
                                (
                                        diagonal
                                                || straight
                                )
                                        && pathClear(
                                        grid,
                                        r,
                                        f,
                                        targetRow,
                                        targetFile
                                )
                        ) {

                            return true;
                        }

                        break;

                    case 'k':

                        if (adr <= 1
                                && adf <= 1
                                && (
                                adr + adf
                                        > 0
                        )) {

                            return true;
                        }

                        break;
                }
            }
        }

        return false;
    }

    private static boolean pathClear(
            char[][] grid,
            int fromRow,
            int fromFile,
            int toRow,
            int toFile
    ) {

        int rowStep =
                Integer.compare(
                        toRow,
                        fromRow
                );

        int fileStep =
                Integer.compare(
                        toFile,
                        fromFile
                );

        int r =
                fromRow
                        + rowStep;

        int f =
                fromFile
                        + fileStep;

        while (
                r != toRow
                        || f != toFile
        ) {

            if (!empty(
                    grid[r][f]
            )) {

                return false;
            }

            r += rowStep;
            f += fileStep;
        }

        return true;
    }

    /*
     * ============================================================
     * BRETT-AUSRICHTUNG
     * ============================================================
     */

    private static int inferWhiteForward(
            char[][] grid
    ) {

        double whitePawnRows = 0;
        double blackPawnRows = 0;

        int whitePawns = 0;
        int blackPawns = 0;

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                char c =
                        norm(
                                grid[r][f]
                        );

                if (c == 'P') {

                    whitePawnRows += r;
                    whitePawns++;

                } else if (c == 'p') {

                    blackPawnRows += r;
                    blackPawns++;
                }
            }
        }

        if (whitePawns > 0
                && blackPawns > 0) {

            double whiteAverage =
                    whitePawnRows
                            / whitePawns;

            double blackAverage =
                    blackPawnRows
                            / blackPawns;

            /*
             * Weiß unten:
             * Weiß bewegt sich Richtung kleinere Zeilen.
             */
            return whiteAverage
                    > blackAverage
                    ? -1
                    : 1;
        }

        /*
         * Fallback über Könige.
         */
        int whiteKingRow = -1;
        int blackKingRow = -1;

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                if (grid[r][f]
                        == 'K') {

                    whiteKingRow = r;

                } else if (
                        grid[r][f]
                                == 'k'
                ) {

                    blackKingRow = r;
                }
            }
        }

        if (whiteKingRow >= 0
                && blackKingRow >= 0) {

            return whiteKingRow
                    > blackKingRow
                    ? -1
                    : 1;
        }

        /*
         * Normale Weiß-Perspektive.
         */
        return -1;
    }

    /*
     * ============================================================
     * HILFSMETHODEN
     * ============================================================
     */

    private static int countRawChanges(
            char[][] a,
            char[][] b
    ) {

        int count = 0;

        for (int r = 0;
             r < 8;
             r++) {

            for (int f = 0;
                 f < 8;
                 f++) {

                if (norm(a[r][f])
                        != norm(b[r][f])) {

                    count++;
                }
            }
        }

        return count;
    }

    private static void overwriteGrid(
            char[][] target,
            char[][] source
    ) {

        for (int r = 0;
             r < 8;
             r++) {

            System.arraycopy(
                    source[r],
                    0,
                    target[r],
                    0,
                    8
            );
        }
    }

    private static char[][] copyGrid(
            char[][] source
    ) {

        char[][] copy =
                new char[8][8];

        for (int r = 0;
             r < 8;
             r++) {

            copy[r] =
                    Arrays.copyOf(
                            source[r],
                            8
                    );
        }

        return copy;
    }

    private static boolean validGrid(
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

    private static boolean inside(
            int row,
            int file
    ) {

        return row >= 0
                && row < 8
                && file >= 0
                && file < 8;
    }

    private static boolean empty(
            char c
    ) {

        return norm(c)
                == '.';
    }

    private static char norm(
            char c
    ) {

        return c == 0
                ? '.'
                : c;
    }

    private static Side sideOf(
            char piece
    ) {

        piece = norm(piece);

        if (piece == '.') {
            return null;
        }

        return Character.isUpperCase(
                piece
        )
                ? Side.WHITE
                : Side.BLACK;
    }

    private static Side opposite(
            Side side
    ) {

        return side == Side.WHITE
                ? Side.BLACK
                : Side.WHITE;
    }
}
