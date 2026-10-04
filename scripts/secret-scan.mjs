import {execFileSync} from 'node:child_process';
const patterns=[
 ['private-key',/-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----/],
 ['github-token',/\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{60,})\b/],
 ['discord-token',/\b[A-Za-z0-9_-]{24,28}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,110}\b/],
 ['cloudflare-global-key',/\b(?:CLOUDFLARE_API_KEY|CF_API_KEY)\s*[:=]\s*["']?[a-f0-9]{37}\b/i],
 ['literal-secret',/\b(?:LICENSE_KEY_PEPPER|SESSION_TOKEN_PEPPER|DISCORD_BOT_TOKEN|ANDROID_KEYSTORE_PASSWORD|ANDROID_KEY_PASSWORD)\s*[:=]\s*["'](?!(?:REPLACE|test|smoke|\$|process\.|System\.))[A-Za-z0-9_+\/-]{20,}["']/]
];
export function categories(path,bytes) {
 const found=[];
 if(/\.(?:jks|keystore|p12|pfx)$/i.test(path))found.push('signing-keystore');
 if(/(?:^|\/)\.env(?:\.[^/]*)?$/.test(path) && !path.endsWith('.example'))found.push('environment-file');
 if(!bytes.includes(0))for(const [name,pattern] of patterns)if(pattern.test(bytes.toString('utf8')))found.push(name);
 return found;
}
if (process.argv.includes('--self-test')) {
 const assert=(condition)=>{if(!condition)throw new Error('scanner self-test');};
 assert(categories('a.txt',Buffer.from('-----BEGIN '+'PRIVATE KEY-----')).includes('private-key'));
 assert(categories('.env',Buffer.from('name=value')).includes('environment-file'));
 assert(categories('release.jks',Buffer.from([0,1,2])).includes('signing-keystore'));
 assert(categories('.env.example',Buffer.from('LICENSE_KEY_PEPPER=REPLACE_WITH_SECRET')).length===0);
 console.log('4 scanner self-tests passed');
} else {
 const git=(args)=>execFileSync('git',args,{maxBuffer:64*1024*1024});
 const lines=git(['rev-list','--objects','--all']).toString().trim().split('\n');
 let checked=0;const findings=[];
 const objects=lines.filter(line=>line.includes(' ')).map(line=>({sha:line.slice(0,line.indexOf(' ')),path:line.slice(line.indexOf(' ')+1)}));
 const batch=execFileSync('git',['cat-file','--batch'],{input:objects.map(o=>o.sha).join('\n')+'\n',maxBuffer:64*1024*1024});
 let offset=0;
 for(const {sha,path} of objects) {
  const end=batch.indexOf(10,offset),header=batch.subarray(offset,end).toString().split(' '),size=Number(header[2]);
  const bytes=batch.subarray(end+1,end+1+size);offset=end+size+2;
  if(header[1]!=='blob')continue;checked++;
  for(const category of categories(path,bytes))findings.push({object:sha,path,category});
 }
 console.log(JSON.stringify({checked_blobs:checked,findings},null,2));
 if(findings.length)process.exitCode=1;
}
