package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R

object DebridFormatterWebPage {
    fun html(context: Context?): String {
        val appName = context?.getString(R.string.app_name) ?: "NuvioTV"
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${'$'}appName - Debrid Formatter</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #000; color: #fff; padding: 24px; }
  h1 { margin-bottom: 16px; }
  label { display: block; margin: 12px 0 4px; font-size: 14px; color: #aaa; }
  textarea { width: 100%; background: #111; color: #fff; border: 1px solid #333; border-radius: 8px; padding: 10px; font-family: monospace; font-size: 13px; resize: vertical; }
  button { margin-top: 16px; padding: 10px 24px; background: #7b5ea7; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; }
  button:hover { background: #9b7ec7; }
  #status { margin-top: 12px; font-size: 13px; color: #aaa; }
</style>
</head>
<body>
<h1>${'$'}appName Debrid Formatter</h1>
<div id="form">
  <label>Name Template</label>
  <textarea id="nameTemplate" rows="3"></textarea>
  <label>Description Template</label>
  <textarea id="descTemplate" rows="4"></textarea>
  <button onclick="save()">Save</button>
  <button onclick="reset()" style="margin-left:8px;background:#333">Reset to Defaults</button>
  <div id="status"></div>
</div>
<script>
async function load() {
  const res = await fetch('/api/settings');
  const data = await res.json();
  document.getElementById('nameTemplate').value = data.settings.nameTemplate || '';
  document.getElementById('descTemplate').value = data.settings.descriptionTemplate || '';
  window._defaults = data.defaults;
}
async function save() {
  const body = { nameTemplate: document.getElementById('nameTemplate').value, descriptionTemplate: document.getElementById('descTemplate').value };
  const res = await fetch('/api/settings', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) });
  document.getElementById('status').textContent = res.ok ? 'Saved!' : 'Error saving.';
}
function reset() {
  if (window._defaults) {
    document.getElementById('nameTemplate').value = window._defaults.nameTemplate || '';
    document.getElementById('descTemplate').value = window._defaults.descriptionTemplate || '';
  }
}
load();
</script>
</body>
</html>"""
    }
}
