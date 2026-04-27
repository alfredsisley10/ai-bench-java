// Standalone Node AF_UNIX bind test — isolates whether the EACCES is
// specific to VSCode's bundled Node or affects any Node on this machine.
//
//   node node-bind-test.js [optional-path]
const net = require('net');
const fs = require('fs');
const os = require('os');
const path = require('path');

const sockPath = process.argv[2] || path.join(os.homedir(), '.ai-bench-copilot.sock');
console.log('node version:', process.version, 'platform:', process.platform);
console.log('attempting bind at:', sockPath);
try { fs.unlinkSync(sockPath); console.log('removed stale file'); } catch (e) {
    if (e.code !== 'ENOENT') console.log('unlink err:', e.code, e.message);
}
const server = net.createServer();
server.on('error', (e) => {
    console.log('LISTEN ERROR — code:', e.code, 'errno:', e.errno,
                'syscall:', e.syscall, 'address:', e.address, 'msg:', e.message);
    process.exit(1);
});
server.listen(sockPath, () => {
    console.log('LISTEN OK — bound; file.exists =', fs.existsSync(sockPath));
    setTimeout(() => { server.close(() => process.exit(0)); }, 500);
});
