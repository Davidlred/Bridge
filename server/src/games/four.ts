import { MoveError, seeded, type DuelModule, type GameMeta } from './module.js';

/** Four in a Row: 7 columns, 6 rows, drop a disc, first to line up four wins. Who goes first comes from the seed. */
export const COLS = 7, ROWS = 6;

export interface FourState {
  players: [string, string];
  /** Row-major from the top: board[row * 7 + col], 0 empty, 1 first player, 2 second. */
  board: number[];
  next: 0 | 1;
  moves: number;
  last: number | null;
  winner: 0 | 1 | null;
  draw: boolean;
  /** The four (or more) winning cells. */
  line: number[];
}

const meta: GameMeta = { id: 'four', name: 'Four in a Row', icon: 'view_column', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 2, tile: 4 };

function lineAt(board: number[], cell: number): number[] {
  const r0 = Math.floor(cell / COLS), c0 = cell % COLS, who = board[cell];
  for (const [dr, dc] of [[0, 1], [1, 0], [1, 1], [1, -1]] as const) {
    const cells = [cell];
    for (const s of [1, -1]) {
      let r = r0 + dr * s, c = c0 + dc * s;
      while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r * COLS + c] === who) { cells.push(r * COLS + c); r += dr * s; c += dc * s; }
    }
    if (cells.length >= 4) return cells.sort((a, b) => a - b);
  }
  return [];
}

export const fourModule: DuelModule<FourState, { col: number }> = {
  kind: 'duel', meta,
  createState: (players, seed) => {
    const first = seeded(`${seed}:first`)() < 0.5 ? 0 : 1;
    return { players, board: Array(COLS * ROWS).fill(0), next: first, moves: 0, last: null, winner: null, draw: false, line: [] };
  },
  applyMove: (s, playerId, move) => {
    if (s.winner !== null || s.draw) throw new MoveError('game_over');
    if (s.players[s.next] !== playerId) throw new MoveError('not_your_turn');
    const col = Number(move?.col);
    if (!Number.isInteger(col) || col < 0 || col >= COLS) throw new MoveError('bad_move');
    let row = -1;
    for (let r = ROWS - 1; r >= 0; r--) if (s.board[r * COLS + col] === 0) { row = r; break; }
    if (row < 0) throw new MoveError('column_full');
    const board = s.board.slice();
    const cell = row * COLS + col;
    board[cell] = s.next + 1;
    const line = lineAt(board, cell);
    const moves = s.moves + 1;
    return { ...s, board, last: cell, moves, line, winner: line.length ? s.next : null, draw: !line.length && moves === COLS * ROWS, next: (1 - s.next) as 0 | 1 };
  },
  isOver: s => ({
    done: s.winner !== null || s.draw,
    winnerId: s.winner !== null ? s.players[s.winner] : undefined,
    scores: { [s.players[0]]: s.winner === 0 ? 1 : 0, [s.players[1]]: s.winner === 1 ? 1 : 0 },
  }),
  turn: s => (s.winner !== null || s.draw ? null : s.players[s.next]),
  viewFor: s => s,
};
