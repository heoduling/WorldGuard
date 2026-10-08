// Minimal protocol-776 clients for this exact core. Packet IDs are exported by the core itself.
// They perform real logins and issue real command packets; server-side probe reads inventories/SQL mailboxes.
const net = require('net');
const http = require('http');
const fs = require('fs');
const crypto = require('crypto');
const ids = JSON.parse(fs.readFileSync(__dirname + '/packet-ids.json', 'utf8'));
const bots = new Map();
function vi(n) { const out=[]; do { let b=n&127; n >>>= 7; if(n) b|=128; out.push(b); }while(n); return Buffer.from(out); }
function rv(b, start=0) { let n=0; for(let i=0;i<5;i++){ if(start+i>=b.length) return null; const x=b[start+i]; n|=(x&127)<<(7*i); if(!(x&128)) return [n>>>0,start+i+1]; } throw Error('bad VarInt'); }
function str(s) { const b=Buffer.from(s,'utf8'); return Buffer.concat([vi(b.length),b]); }
function uuid(name) { const b=crypto.createHash('md5').update('OfflinePlayer:'+name).digest(); b[6]=(b[6]&15)|48; b[8]=(b[8]&63)|128; return b; }
function key(map, s) { const n=ids[map]['minecraft:'+s]; if(n===undefined) throw Error('missing packet '+map+'.'+s); return n; }
class Bot {
  constructor(name) {
    this.name=name; this.state='login'; this.buffer=Buffer.alloc(0); this.error=null;
    this.containerId=0; this.containerStateId=0; this.clicksSent=0;
    this.socket=net.createConnection({host:'127.0.0.1',port:53486});
    this.socket.setNoDelay(true);
    this.socket.on('connect',()=>{
      const port=Buffer.alloc(2); port.writeUInt16BE(53486);
      this.packet(0,Buffer.concat([vi(ids.protocol),str('127.0.0.1'),port,vi(2)]));
      this.packet(key('loginIn','hello'),Buffer.concat([str(name),uuid(name)]));
    });
    this.socket.on('data',b=>{ try { this.buffer=Buffer.concat([this.buffer,b]); this.parse(); } catch(e){this.error=e.message;this.socket.destroy();} });
    this.socket.on('error',e=>{this.error=e.message;});
    this.socket.on('close',()=>{this.state='closed'; console.log('BOT_CLOSED',name,this.error||'normal');});
  }
  packet(id,payload=Buffer.alloc(0)){ const data=Buffer.concat([vi(id),payload]); this.socket.write(Buffer.concat([vi(data.length),data])); }
  parse(){
    while(true){const h=rv(this.buffer); if(!h || this.buffer.length<h[1]+h[0]) return;
      const frame=this.buffer.subarray(h[1],h[1]+h[0]); this.buffer=this.buffer.subarray(h[1]+h[0]);
      const p=rv(frame); const id=p[0],payload=frame.subarray(p[1]); this.handle(id,payload);
    }
  }
  handle(id,p){
    const map=this.state==='login'?'loginOut':this.state==='config'?'configOut':'playOut';
    const type=Object.keys(ids[map]).find(k=>ids[map][k]===id)?.replace('minecraft:','');
    if(type==='disconnect'){this.error='server disconnect '+p.toString('hex').slice(0,500);this.socket.end();return;}
    if(this.state==='login'){
      if(type==='login_compression') throw Error('compression should be disabled');
      if(type==='login_finished'){
        this.packet(key('loginIn','login_acknowledged')); this.state='config';
      }
    }else if(this.state==='config'){
      if(type==='select_known_packs') this.packet(key('configIn','select_known_packs'),p);
      if(type==='keep_alive') this.packet(key('configIn','keep_alive'),p);
      if(type==='ping') this.packet(key('configIn','pong'),p);
      if(type==='finish_configuration'){
        this.packet(key('configIn','finish_configuration'));this.state='play';console.log('BOT_PLAY',this.name);
      }
    }else if(this.state==='play'){
      if(type==='open_screen') { this.containerId=rv(p)[0]; this.containerStateId=0; }
      if(type==='container_set_content') { const c=rv(p); if(c[0]===this.containerId) this.containerStateId=rv(p,c[1])[0]; }
      if(type==='container_close') this.containerId=0;
      if(type==='keep_alive') this.packet(key('playIn','keep_alive'),p);
      if(type==='ping') this.packet(key('playIn','pong'),p);
      if(type==='player_position') this.packet(key('playIn','accept_teleportation'),vi(rv(p)[0]));
    }
  }
  send(command){if(this.state!=='play') throw Error(this.name+' not in play: '+this.state);this.packet(key('playIn','chat_command'),str(command));}
  click(slot=-999){
    if(this.state!=='play' || !this.containerId) throw Error('No open container');
    const rawSlot=Buffer.alloc(2); rawSlot.writeInt16BE(slot);
    // Exact core codec: ContainerId, VarInt state, short slot, byte button,
    // VarInt PICKUP, empty changed-slots map, optional EMPTY carried stack.
    this.packet(key('playIn','container_click'),Buffer.concat([
      vi(this.containerId),vi(this.containerStateId),rawSlot,Buffer.from([0]),vi(0),vi(0),Buffer.from([0])
    ])); this.clicksSent++;
  }
  closeWindow(){this.packet(key('playIn','container_close'),vi(this.containerId));this.containerId=0;}
}
http.createServer(async(req,res)=>{
  try{
    let data='';for await(const b of req)data+=b;
    const input=data?JSON.parse(data):{action:'status'};
    if(input.action==='connect'){
      const existing=bots.get(input.name);if(existing&&existing.state!=='closed')throw Error('already connected');
      bots.set(input.name,new Bot(input.name));
    }
    if(input.action==='send')bots.get(input.name).send(input.command.replace(/^\//,''));
    if(input.action==='click'){
      for(let i=0;i<(input.count||1);i++){
        bots.get(input.name).click(input.slot??-999);
        await new Promise(resolve=>setTimeout(resolve,50));
      }
    }
    if(input.action==='close_window')bots.get(input.name).closeWindow();
    if(input.action==='quit')bots.get(input.name).socket.end();
    const result=Array.from(bots.values(),b=>({name:b.name,state:b.state,error:b.error,
      containerId:b.containerId,containerStateId:b.containerStateId,clicksSent:b.clicksSent}));
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(result));
  }catch(e){res.writeHead(500);res.end(e.stack);}
}).listen(53487,'127.0.0.1',()=>console.log('BOT_CONTROL_READY 53487 protocol='+ids.protocol));
