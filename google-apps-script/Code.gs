const MASTER_SHEET_ID = 'PUT_MASTER_SPREADSHEET_ID_HERE';

function doPost(e) {
  try {
    const input = JSON.parse(e.postData.contents || '{}');
    if (input.action !== 'sync' || !Array.isArray(input.events)) throw new Error('Invalid payload');
    if (MASTER_SHEET_ID.indexOf('PUT_') === 0) throw new Error('Set MASTER_SHEET_ID first');
    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      const book = SpreadsheetApp.openById(MASTER_SHEET_ID);
      let sheet = book.getSheetByName('Incoming');
      if (!sheet) {
        sheet = book.insertSheet('Incoming');
        sheet.appendRow(['Event ID', 'Type', 'Payload JSON', 'Created At', 'Device ID']);
      }
      const last = sheet.getLastRow();
      const seen = new Set(last > 1 ? sheet.getRange(2, 1, last - 1, 1).getValues().flat().map(String) : []);
      const rows = [];
      input.events.forEach(ev => {
        const id = String(ev.id || '');
        if (!id || seen.has(id)) return;
        rows.push([id, String(ev.type || ''), JSON.stringify(ev.payload || {}),
          new Date(Number(ev.createdAt || Date.now())), String(input.deviceId || '')]);
        seen.add(id);
      });
      if (rows.length) sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, 5).setValues(rows);
      return json({ok: true, accepted: rows.length});
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return json({ok: false, error: String(err)});
  }
}
function json(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}
