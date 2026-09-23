const { app, BrowserWindow } = require('electron');
const path = require('path');

function createWindow() {
  const win = new BrowserWindow({
    width: 560,
    height: 860,
    title: 'PDF 阅读器',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js'),
      webSecurity: false
    }
  });
  win.loadFile('viewer.html');
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
