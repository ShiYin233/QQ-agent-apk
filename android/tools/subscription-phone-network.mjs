// Connectivity only: no bearer tokens, account data, authorization codes or inference.
const targets=[
  ['google-account','https://accounts.google.com/.well-known/openid-configuration'],
  ['google-token','https://oauth2.googleapis.com/token',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:'grant_type=invalid'}],
  ['google-model-service','https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist',{method:'POST',headers:{'content-type':'application/json'},body:'{}'}],
  ['openai-identity','https://auth.openai.com/.well-known/jwks.json'],
  ['openai-model-service','https://api.openai.com/v1/models']
];
const services=await Promise.all(targets.map(async([service,url,options={}])=>{
  try{
    const response=await fetch(url,{...options,signal:AbortSignal.timeout(10000)});
    await response.body?.cancel();return {service,reachable:true,httpStatus:response.status};
  }catch(error){
    const raw=String(error.cause?.code || error.name || 'NetworkError');
    return {service,reachable:false,errorCode:/^[A-Z_a-z0-9]{1,64}$/.test(raw)?raw:'NetworkError'};
  }
}));
console.log(JSON.stringify({usesRealCredentials:false,modelCalls:0,qqMessagesSent:0,services}));
