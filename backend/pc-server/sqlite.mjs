import { DatabaseSync, backup } from "node:sqlite";

class Statement {
  constructor(database, sql, bindings = []) {
    this.database = database;
    this.sql = sql;
    this.bindings = bindings;
  }
  bind(...bindings) {
    return new Statement(this.database, this.sql, bindings);
  }
  execute(kind) {
    const statement = this.database.native.prepare(this.sql);
    const reads = statement.columns().length > 0;
    const results = reads ? statement.all(...this.bindings) : [];
    let changes = 0;
    if (!reads) changes = Number(statement.run(...this.bindings).changes);
    else if (!/^\s*(?:SELECT|PRAGMA|EXPLAIN)\b/i.test(this.sql))
      changes = Number(
        this.database.native.prepare("SELECT changes() AS count").get().count,
      );
    if (kind === "first") return results[0] ?? null;
    return { success: true, results, meta: { changes, duration: 0 } };
  }
  async first(column) {
    const row = this.execute("first");
    return column === undefined ? row : (row?.[column] ?? null);
  }
  async all() {
    return this.execute("all");
  }
  async run() {
    return this.execute("run");
  }
}

export class PcDatabase {
  constructor(path) {
    this.native = new DatabaseSync(path);
    try {
      this.native.exec(
        "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=3000;",
      );
    } catch (error) {
      this.native.close();
      throw error;
    }
  }
  prepare(sql) {
    return new Statement(this, sql);
  }
  async exec(sql) {
    this.native.exec(sql);
    return { count: 0, duration: 0 };
  }
  async batch(statements) {
    this.native.exec("BEGIN IMMEDIATE");
    try {
      const results = statements.map((statement) => {
        if (!(statement instanceof Statement) || statement.database !== this)
          throw new Error("invalid batch statement");
        return statement.execute("run");
      });
      this.native.exec("COMMIT");
      return results;
    } catch (error) {
      this.native.exec("ROLLBACK");
      throw error;
    }
  }
  integrity() {
    return (
      this.native.prepare("PRAGMA integrity_check").get().integrity_check ===
        "ok" &&
      this.native.prepare("PRAGMA foreign_key_check").all().length === 0
    );
  }
  async backup(path) {
    return backup(this.native, path);
  }
  close() {
    this.native.close();
  }
}
