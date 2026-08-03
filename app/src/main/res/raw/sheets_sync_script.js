/**
 * AllinOne — Money Manager · Google Sheets sync backend (v2)
 *
 * Deploy: Extensions ▸ Apps Script ▸ paste this ▸ Save ▸ Deploy ▸ New deployment
 *         ▸ Web app ▸ Execute as "Me" ▸ Who has access "Anyone" ▸ Deploy.
 * Then paste the /exec URL into the app (Settings ▸ Google Sheets sync).
 *
 * The app PUSHes a full snapshot (transactions, categories, people, loans,
 * repayments, recurring rules, settings) and can PULL it all back on a fresh
 * install. Every tab is rewritten on each push, so the sheet always mirrors
 * the phone exactly.
 */

// Bumped whenever this file changes. Open the /exec URL with ?action=ping to see
// which version is live — editing the script without deploying a new version leaves
// the old one serving the URL.
var VERSION = 11;

// Tab name  →  column headers. The app reads/writes by header name, so you may
// reorder columns in the sheet; just don't rename the headers.
var TABS = [
  { key: 'transactions', name: 'Transactions',
    headers: ['id', 'date', 'type', 'category', 'amount', 'note', 'date_ms'] },
  { key: 'categories',   name: 'Categories',
    headers: ['id', 'name', 'color', 'type', 'budget'] },
  { key: 'people',       name: 'People',
    headers: ['id', 'name', 'phone', 'note'] },
  { key: 'loans',        name: 'Loans',
    headers: ['id', 'person_id', 'person_name', 'direction', 'principal',
              'paid', 'outstanding', 'date', 'date_ms', 'due_ms', 'note', 'status'] },
  { key: 'payments',     name: 'LoanPayments',
    headers: ['id', 'loan_id', 'amount', 'date', 'date_ms'] },
  { key: 'recurring',    name: 'Recurring',
    headers: ['id', 'type', 'category', 'amount', 'note',
              'interval_type', 'interval_n', 'next_date', 'next_ms', 'enabled'] }
];

var SETTINGS_TAB = 'Settings';

// Column formats, keyed by header name. These are pinned explicitly because a
// column that ever held dates keeps its date format: ids like 58 then render as
// 1900-02-26, and getValues() hands them back as Date objects, which would wreck
// the loan ⇄ person and repayment ⇄ loan links on restore.
var COUNT_COLS = ['id', 'person_id', 'loan_id', 'color', 'interval_n', 'enabled'];
var MS_COLS    = ['date_ms', 'due_ms', 'next_ms'];
var INT_COLS   = COUNT_COLS.concat(MS_COLS);
var MONEY_COLS = ['amount', 'principal', 'paid', 'outstanding', 'budget'];
var DATE_COLS  = ['date', 'next_date'];

// Columns the app needs but a person never reads — hidden to keep the sheet clean.
// Hiding does not affect reads, so a pull still gets them.
var MACHINE_COLS = ['color', 'date_ms', 'due_ms', 'next_ms'];

// The reference sheet's green table theme. Its cells carry no formatting at all —
// the whole look comes from a Google Sheets Table, so applyTable() reproduces it
// exactly. These values are also used by the paint-on fallback for when the Sheets
// API service is unavailable.
var HEADER_BG   = '#356854';   // header bar
var HEADER_FG   = '#FFFFFF';
var BORDER      = '#284E3F';   // outer table edge
var ROW_A       = '#FFFFFF';   // banding, first row
var ROW_B       = '#F6F8F9';   // banding, second row — also the row separator colour
var TEXT        = '#434343';   // body text
var FONT        = 'Roboto';
var FONT_SIZE   = 10;
var HEADER_H    = 32;
var MAX_COL_W   = 320;         // keeps a long note from stretching the sheet

// Columns that are a fixed set of values get a dropdown, so the sheet stays
// editable without anyone having to remember the exact spelling.
var ENUMS = {
  type:          ['EXPENSE', 'INCOME'],
  direction:     ['LENT', 'BORROWED'],
  status:        ['OPEN', 'SETTLED'],
  interval_type: ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY']
};

// Colours behind each dropdown value — this is what makes them read as chips.
var CHIPS = {
  INCOME:   { bg: '#E6F4EA', fg: '#137333' },
  EXPENSE:  { bg: '#FCE8E6', fg: '#C5221F' },
  LENT:     { bg: '#E6F4EA', fg: '#137333' },
  BORROWED: { bg: '#FCE8E6', fg: '#C5221F' },
  OPEN:     { bg: '#FEF7E0', fg: '#B06000' },
  SETTLED:  { bg: '#E8EAED', fg: '#3C4043' },
  DAILY:    { bg: '#E8F0FE', fg: '#1967D2' },
  WEEKLY:   { bg: '#E8F0FE', fg: '#1967D2' },
  MONTHLY:  { bg: '#E8F0FE', fg: '#1967D2' },
  YEARLY:   { bg: '#E8F0FE', fg: '#1967D2' }
};

// Filled from the pushed payload so Transactions can offer the real category list
// and Loans the real people — reset on every push.
var CATEGORY_NAMES = [];
var PERSON_NAMES = [];

// Styling is best-effort so it can never cost the data write, but swallowing the
// failure silently makes "no design applied" impossible to diagnose. Each skipped
// step is recorded here and returned with the push.
var STYLE_WARNINGS = [];

// Table work is batched: one metadata read at the start of a push, one batchUpdate
// at the end. Per-tab calls meant 21 REST round trips and a timed-out sync.
var TABLE_API = null;      // null = not probed yet
var SS_ID = null;
var OLD_TABLE_IDS = [];
var PENDING_TABLES = [];

function note(step, err) {
  var msg = step + ' — ' + message(err);
  if (STYLE_WARNINGS.indexOf(msg) < 0) STYLE_WARNINGS.push(msg);
}

// ── Entry points ──────────────────────────────────────────────────────────────

/** GET ?action=pull  → the whole snapshot as JSON.  ?action=ping → health check. */
function doGet(e) {
  var action = (e && e.parameter && e.parameter.action) || 'pull';
  try {
    if (action === 'ping') return json({ ok: true, version: VERSION });
    return json(pull());
  } catch (err) {
    return json({ ok: false, error: message(err) });
  }
}

/** POST {action:'push', data:{…}}  → rewrites every tab. action:'pull' also works. */
function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      throw new Error('the request carried no body — it did not arrive as a real POST');
    }
    var body = JSON.parse(e.postData.contents);
    if (body.action === 'pull') return json(pull());
    return json(push(body.data || body));
  } catch (err) {
    return json({ ok: false, error: message(err) });
  }
}

// ── Push (phone → sheet) ──────────────────────────────────────────────────────

function push(data) {
  data = data || {};
  STYLE_WARNINGS = [];
  TABLE_API = null; OLD_TABLE_IDS = []; PENDING_TABLES = [];
  tableApiReady();   // one probe + metadata read for the whole push
  // Gather the lists the dropdowns offer before any tab is written.
  CATEGORY_NAMES = namesIn(data.categories, 'name');
  PERSON_NAMES = namesIn(data.people, 'name');

  var counts = {};
  var matched = 0;
  var failures = [];
  for (var i = 0; i < TABS.length; i++) {
    var t = TABS[i];
    // A tab missing from the payload is left untouched rather than wiped.
    if (!data[t.key]) continue;
    matched++;
    try {
      counts[t.key] = writeTab(t, data[t.key]);
    } catch (err) {
      // Carry on to the other tabs. Transactions is written first, so letting its
      // failure propagate meant one bad tab silently cost the whole backup.
      failures.push('"' + t.name + '" — ' + message(err));
    }
  }
  // Reporting success for a payload that matched nothing is how an upload appears
  // to work while the sheet stays empty. Say what actually arrived instead.
  if (matched === 0) {
    throw new Error('the payload held no recognised tab data. Keys received: ['
      + Object.keys(data).join(', ') + ']');
  }
  try {
    writeSettings(data.settings || {});
  } catch (err) {
    failures.push('"' + SETTINGS_TAB + '" — ' + message(err));
  }
  flushTables();   // every table change in one request
  if (failures.length) {
    return { ok: false, version: VERSION, counts: counts, warnings: STYLE_WARNINGS,
             error: failures.join('  |  '), syncedAt: stamp() };
  }
  return { ok: true, version: VERSION, counts: counts, warnings: STYLE_WARNINGS,
           syncedAt: stamp() };
}

function writeTab(tab, rows) {
  var out = [tab.headers];
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    var line = [];
    for (var c = 0; c < tab.headers.length; c++) {
      var v = row[tab.headers[c]];
      line.push(v === undefined || v === null ? '' : v);
    }
    out.push(line);
  }

  try {
    writeInto(sheetFor(tab.name), tab, out);
  } catch (err) {
    // Something owns this tab and will not accept a plain rewrite — a Google Sheets
    // Table with typed columns being the usual culprit. The tab is a mirror of the
    // phone, so rebuilding it from scratch loses nothing and always works.
    writeInto(recreateSheet(tab.name), tab, out);
  }
  return rows.length;
}

function writeInto(sheet, tab, out) {
  // setValues() writes into a fixed range and cannot grow the sheet, so the grid
  // has to be big enough first.
  ensureSize(sheet, out.length, tab.headers.length);
  // Last push's table has to go before clear() — its typed columns refuse writes.
  dropTables(sheet);
  sheet.clear();
  // Formats next, so each value lands as the right type rather than being
  // re-interpreted by whatever format the column happened to carry.
  applyFormats(sheet, tab.headers, true, out.length);
  sheet.getRange(1, 1, out.length, tab.headers.length).setValues(out);
  // The table is queued now and sent once, after every tab has been written.
  queueTable(sheet, tab, out.length);
  styleSheet(sheet, tab.headers, out.length, TABLE_API);
}

/** Replaces a tab with an empty one in the same position, dropping any table,
 *  typed columns or stale formatting along with it. */
function recreateSheet(name) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var old = ss.getSheetByName(name);
  if (!old) return ss.insertSheet(name);
  var index = old.getIndex() - 1;
  // A spreadsheet must always keep one sheet, so the replacement goes in first.
  var fresh = ss.insertSheet(name + ' (rebuilding)', index);
  ss.deleteSheet(old);
  fresh.setName(name);
  return fresh;
}

function writeSettings(settings) {
  var out = [['key', 'value']];
  for (var k in settings) out.push([k, settings[k]]);
  out.push(['last_push', stamp()]);

  var tab = { name: SETTINGS_TAB, headers: ['key', 'value'] };
  try {
    writeInto(sheetFor(SETTINGS_TAB), tab, out);
  } catch (err) {
    writeInto(recreateSheet(SETTINGS_TAB), tab, out);
  }
}

// ── Pull (sheet → phone) ──────────────────────────────────────────────────────

function pull() {
  var out = { ok: true, version: VERSION, pulledAt: stamp() };
  for (var i = 0; i < TABS.length; i++) {
    var t = TABS[i];
    try {
      out[t.key] = readTab(t.name);
    } catch (err) {
      throw new Error('"' + t.name + '" tab — ' + message(err));
    }
  }
  out.settings = readSettings();
  return out;
}

function readTab(name) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
  var rows = [];
  if (!sheet) return rows;

  if (sheet.getLastColumn() < 1 || sheet.getLastRow() < 2) return rows;

  var headerRow = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var headers = [];
  for (var c = 0; c < headerRow.length; c++) {
    headers.push(String(headerRow[c]).trim().toLowerCase());
  }
  // Repair a sheet whose numeric columns picked up a date format, then read. Doing
  // this before getValues() is what makes ids come back as 58 and not as a Date.
  applyFormats(sheet, headers, false);

  var values = sheet.getDataRange().getValues();
  if (values.length < 2) return rows;

  for (var r = 1; r < values.length; r++) {
    var obj = {};
    var blank = true;
    for (var c2 = 0; c2 < headers.length; c2++) {
      if (!headers[c2]) continue;
      var v = values[r][c2];
      if (v instanceof Date) v = undate(headers[c2], v);
      obj[headers[c2]] = v;
      if (v !== '' && v !== null) blank = false;
    }
    if (!blank) rows.push(obj);
  }
  return rows;
}

/**
 * getValues() returns a Date for any date-formatted cell, so a column stuck on a
 * date format hands back 1900-02-26 where the sheet really stores the number 58.
 * Counting columns are converted back to that number; genuine timestamp columns
 * become epoch millis, which is what the app expects.
 */
function undate(header, d) {
  if (COUNT_COLS.indexOf(header) >= 0) {
    var epoch = new Date(1899, 11, 30, 0, 0, 0, 0);
    return Math.round((d.getTime() - epoch.getTime()) / 86400000);
  }
  return d.getTime();
}

function readSettings() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SETTINGS_TAB);
  var out = {};
  if (!sheet) return out;
  var values = sheet.getDataRange().getValues();
  for (var r = 1; r < values.length; r++) {
    var k = String(values[r][0]).trim();
    if (k) out[k] = values[r][1];
  }
  return out;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function sheetFor(name) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(name) || ss.insertSheet(name);
}

/** Grows the grid so a setValues() range always fits. Sheets never shrinks here. */
function ensureSize(sheet, needRows, needCols) {
  var maxRows = sheet.getMaxRows();
  if (needRows > maxRows) sheet.insertRowsAfter(maxRows, needRows - maxRows);
  var maxCols = sheet.getMaxColumns();
  if (needCols > maxCols) sheet.insertColumnsAfter(maxCols, needCols - maxCols);
}

function message(err) {
  if (!err) return 'unknown error';
  return String(err.message || err);
}

function formatFor(header) {
  if (INT_COLS.indexOf(header) >= 0) return '0';
  if (MONEY_COLS.indexOf(header) >= 0) return '0.00';
  if (DATE_COLS.indexOf(header) >= 0) return 'yyyy-mm-dd';
  return '@'; // plain text
}

/**
 * Pins an explicit number format on every data column, down the whole sheet so
 * rows added later inherit it too. Row 1 is left alone — it holds the headers.
 */
function applyFormats(sheet, headers, mayDropTables, dataRows) {
  // Bounded to the rows that actually hold data. Formatting the full 1000-row grid
  // for every column of every tab is what pushed a sync past its timeout.
  var last = dataRows || sheet.getLastRow() || sheet.getMaxRows();
  var rows = Math.min(last, sheet.getMaxRows()) - 1;
  if (rows < 1) return;
  // Only a push may restructure the sheet. A pull must not change what it reads.
  if (mayDropTables) dropTables(sheet);
  var cols = sheet.getMaxColumns();
  for (var c = 0; c < headers.length && c < cols; c++) {
    if (!headers[c]) continue;
    try {
      sheet.getRange(2, c + 1, rows, 1).setNumberFormat(formatFor(headers[c]));
    } catch (err) {
      // A typed column in a Google Sheets Table rejects number formats. Formatting
      // is a nicety — never fail a sync over it. Reads stay safe because ids are
      // sanity-checked on the way back into the app.
    }
  }
}

/**
 * A tab converted to a Google Sheets Table has typed columns, and setNumberFormat
 * throws on those ("You can't set the number format of cells in a typed column").
 * This script rewrites each tab wholesale on every push, so a table buys nothing
 * here — remove it where the runtime supports doing so.
 */
function dropTables(sheet) {
  try {
    if (typeof sheet.getTables !== 'function') return;
    var tables = sheet.getTables();
    for (var i = 0; i < tables.length; i++) {
      if (tables[i] && typeof tables[i].remove === 'function') tables[i].remove();
    }
  } catch (err) {
    // Older runtime, or a table that will not budge — the per-column catch copes.
  }
}

/**
 * The sheet's look: dark header bar, banded rows, aligned columns, machine columns
 * hidden. Every step is individually guarded — styling must never throw, or writeTab
 * would take it as a refused write and needlessly rebuild the tab.
 */
function styleSheet(sheet, headers, totalRows, tabled) {
  var cols = headers.length;

  // When a real Table is in place it draws the header, stripes and chips itself —
  // and a cell fill would paint over its banding. The reference sheet has no cell
  // styling at all for exactly this reason, so the paint-on version is only the
  // stand-in for when the Table could not be created.
  if (!tabled) {
    try {
      // Body first, so the header styling below wins on row 1.
      sheet.getRange(1, 1, Math.max(totalRows, 1), cols)
        .setFontFamily(FONT)
        .setFontSize(FONT_SIZE)
        .setFontColor(TEXT)
        .setVerticalAlignment('middle');

      sheet.getRange(1, 1, 1, cols)
        .setFontWeight('bold')
        .setFontColor(HEADER_FG)
        .setBackground(HEADER_BG);
    } catch (err) { note('header/font', err); }

    try {
      // Light separators inside, a dark edge around — the reference sheet's borders.
      var all = sheet.getRange(1, 1, Math.max(totalRows, 1), cols);
      all.setBorder(null, null, null, null, false, true, ROW_B, SpreadsheetApp.BorderStyle.SOLID);
      all.setBorder(true, true, true, true, null, null, BORDER, SpreadsheetApp.BorderStyle.SOLID);
    } catch (err) { note('borders', err); }

    try {
      // Old bandings must go first — they cannot overlap.
      var bandings = sheet.getBandings();
      for (var i = 0; i < bandings.length; i++) bandings[i].remove();
      if (totalRows > 1) {
        sheet.getRange(2, 1, totalRows - 1, cols)
          .applyRowBanding()
          .setHeaderRowColor(null)
          .setFirstRowColor(ROW_A)
          .setSecondRowColor(ROW_B);
      }
    } catch (err) { note('row banding', err); }
  }

  try {
    sheet.setFrozenRows(1);
    sheet.setRowHeight(1, HEADER_H);
    sheet.setTabColor(HEADER_BG);
  } catch (err) { note('freeze/tab colour', err); }

  try {
    for (var c = 0; c < cols; c++) {
      var h = headers[c];
      sheet.getRange(1, c + 1, Math.max(totalRows, 1), 1).setHorizontalAlignment(alignFor(h));
      if (MACHINE_COLS.indexOf(h) >= 0) sheet.hideColumns(c + 1);
      else sheet.showColumns(c + 1);
    }
  } catch (err) { note('alignment/hidden columns', err); }

  try {
    sheet.autoResizeColumns(1, cols);
    for (var w = 1; w <= cols; w++) {
      if (sheet.getColumnWidth(w) > MAX_COL_W) sheet.setColumnWidth(w, MAX_COL_W);
    }
    sheet.setHiddenGridlines(true); // banding reads cleaner without them
  } catch (err) { note('column widths/gridlines', err); }

  // A Table's DROPDOWN columns render their own chips, so these would be duplicates.
  if (!tabled) {
    applyDropdowns(sheet, headers, totalRows);
    applyChipColors(sheet, headers, totalRows);
  }
}

/**
 * Recreates the Google Sheets Table that gives the reference sheet its look — green
 * header, striped rows and real dropdown chips. Cell formatting cannot reproduce
 * this; a Table renders it natively.
 *
 * Requires the advanced Sheets service: Apps Script editor ▸ Services ▸ add
 * "Google Sheets API". Without it this is skipped and the plain styling above
 * stands in, with a warning saying so.
 *
 * Column types are set explicitly, which also stops Sheets inferring a date type
 * for an id column — the very thing that once made ids read back as 1900 dates.
 */
function tableApiReady() {
  if (TABLE_API !== null) return TABLE_API;
  TABLE_API = false;
  if (typeof Sheets === 'undefined' || !Sheets.Spreadsheets) {
    note('table styling', new Error(
      'the Google Sheets API service is not enabled — add it in the Apps Script ' +
      'editor under Services to get the table look'));
    return TABLE_API;
  }
  try {
    // One metadata read for the whole push: proves the service works and lists the
    // tables left by the previous push, which must be deleted before new ones land.
    SS_ID = SpreadsheetApp.getActiveSpreadsheet().getId();
    var meta = Sheets.Spreadsheets.get(SS_ID, { fields: 'sheets(tables(tableId))' });
    OLD_TABLE_IDS = [];
    for (var i = 0; meta.sheets && i < meta.sheets.length; i++) {
      var tables = meta.sheets[i].tables || [];
      for (var t = 0; t < tables.length; t++) OLD_TABLE_IDS.push(tables[t].tableId);
    }
    TABLE_API = true;
  } catch (err) {
    note('reaching the Sheets API', err);
  }
  return TABLE_API;
}

/** Queues a tab's table. Nothing is sent until flushTables() runs. */
function queueTable(sheet, tab, totalRows) {
  if (!TABLE_API || totalRows < 2) return;
  try {
    PENDING_TABLES.push({
      addTable: {
        table: {
          name: tab.name,
          range: {
            sheetId: sheet.getSheetId(),
            startRowIndex: 0, endRowIndex: totalRows,
            startColumnIndex: 0, endColumnIndex: tab.headers.length
          },
          rowsProperties: {
            headerColorStyle:     { rgbColor: rgb(HEADER_BG) },
            firstBandColorStyle:  { rgbColor: rgb(ROW_A) },
            secondBandColorStyle: { rgbColor: rgb(ROW_B) }
          },
          columnProperties: columnProperties(tab.headers)
        }
      }
    });
  } catch (err) {
    // Must never escape — this runs mid-write and losing data to styling is not a trade.
    note('queueing the "' + tab.name + '" table', err);
  }
}

/**
 * Sends every table change in a single batch. Doing this per tab meant three REST
 * round trips each — 21 for a full push, which is what made the sync time out.
 */
function flushTables() {
  if (!TABLE_API || !PENDING_TABLES.length) return;
  try {
    var requests = [];
    for (var i = 0; i < OLD_TABLE_IDS.length; i++) {
      requests.push({ deleteTable: { tableId: OLD_TABLE_IDS[i] } });
    }
    requests = requests.concat(PENDING_TABLES);
    Sheets.Spreadsheets.batchUpdate({ requests: requests }, SS_ID);
  } catch (err) {
    note('applying the table design', err);
  }
  PENDING_TABLES = [];
}

function columnProperties(headers) {
  var props = [];
  for (var i = 0; i < headers.length; i++) {
    var h = headers[i];
    var p = { columnIndex: i, columnName: h, columnType: 'TEXT' };
    var list = ENUMS[h];
    if (list) {
      p.columnType = 'DROPDOWN';
      p.dataValidationRule = {
        condition: { type: 'ONE_OF_LIST', values: valuesOf(list) }
      };
    } else if (DATE_COLS.indexOf(h) >= 0) {
      p.columnType = 'DATE';
    } else if (INT_COLS.indexOf(h) >= 0 || MONEY_COLS.indexOf(h) >= 0) {
      // Never DATE: an id typed as a date is what once corrupted every row link.
      p.columnType = 'DOUBLE';
    }
    props.push(p);
  }
  return props;
}

function valuesOf(list) {
  var out = [];
  for (var i = 0; i < list.length; i++) out.push({ userEnteredValue: list[i] });
  return out;
}

/** "#356854" → {red,green,blue} in the 0-1 range the Sheets API expects. */
function rgb(hex) {
  var h = String(hex).replace('#', '');
  return {
    red:   parseInt(h.substring(0, 2), 16) / 255,
    green: parseInt(h.substring(2, 4), 16) / 255,
    blue:  parseInt(h.substring(4, 6), 16) / 255
  };
}

/** Unique non-empty values of a field across the pushed rows. */
function namesIn(rows, field) {
  var out = [];
  if (!rows) return out;
  for (var i = 0; i < rows.length; i++) {
    var v = rows[i] ? rows[i][field] : null;
    if (v === undefined || v === null || v === '') continue;
    v = String(v);
    if (out.indexOf(v) < 0) out.push(v);
  }
  return out;
}

/** The dropdown list for a column, or null if it is free text. */
function listFor(header) {
  if (ENUMS[header]) return ENUMS[header];
  if (header === 'category' && CATEGORY_NAMES.length) return CATEGORY_NAMES;
  if (header === 'person_name' && PERSON_NAMES.length) return PERSON_NAMES;
  return null;
}

/**
 * Dropdowns on the fixed-value columns and a checkbox on `enabled`. Applied to the
 * data rows only, so empty rows below stay clean. Guarded throughout — a refused
 * dropdown must never cost the write.
 */
function applyDropdowns(sheet, headers, totalRows) {
  var maxRows = Math.min(Math.max(totalRows, 2), sheet.getMaxRows());
  for (var c = 0; c < headers.length; c++) {
    var h = headers[c];
    var col = c + 1;
    try {
      // Drop stale rules left by a previous, longer sync.
      if (maxRows > 1) sheet.getRange(2, col, maxRows - 1, 1).clearDataValidations();
    } catch (err) {}
    if (totalRows < 2) continue;
    try {
      var range = sheet.getRange(2, col, totalRows - 1, 1);
      if (h === 'enabled') {
        range.setDataValidation(SpreadsheetApp.newDataValidation()
          .requireCheckbox(1, 0).build());
        continue;
      }
      var list = listFor(h);
      if (!list) continue;
      // Categories and people are allowed to be invalid: a transaction may still
      // reference a category that has since been deleted, and that is not an error.
      var strict = !!ENUMS[h];
      range.setDataValidation(SpreadsheetApp.newDataValidation()
        .requireValueInList(list, true)
        .setAllowInvalid(!strict)
        .build());
    } catch (err) { note('dropdown on "' + h + '"', err); }
  }
}

/** Colours each dropdown value so it reads as a chip rather than plain text. */
function applyChipColors(sheet, headers, totalRows) {
  try {
    var rules = [];
    if (totalRows > 1) {
      for (var c = 0; c < headers.length; c++) {
        var values = ENUMS[headers[c]];
        if (!values) continue;
        var range = sheet.getRange(2, c + 1, totalRows - 1, 1);
        for (var v = 0; v < values.length; v++) {
          var chip = CHIPS[values[v]];
          if (!chip) continue;
          rules.push(SpreadsheetApp.newConditionalFormatRule()
            .whenTextEqualTo(values[v])
            .setBackground(chip.bg)
            .setFontColor(chip.fg)
            .setRanges([range])
            .build());
        }
      }
    }
    // Always set (even to an empty list) so old rules do not accumulate.
    sheet.setConditionalFormatRules(rules);
  } catch (err) { note('chip colours', err); }
}

/** Numbers right, dates centred, everything else left. */
function alignFor(header) {
  if (MONEY_COLS.indexOf(header) >= 0 || INT_COLS.indexOf(header) >= 0) return 'right';
  if (DATE_COLS.indexOf(header) >= 0) return 'center';
  return 'left';
}

function stamp() {
  return Utilities.formatDate(new Date(),
    Session.getScriptTimeZone(), 'yyyy-MM-dd HH:mm:ss');
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}