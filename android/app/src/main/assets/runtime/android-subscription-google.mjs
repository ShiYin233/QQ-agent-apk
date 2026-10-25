import { setTimeout as delay } from 'node:timers/promises';

const endpoint='https://cloudcode-pa.googleapis.com/v1internal';
const metadata={ideType:'IDE_UNSPECIFIED',platform:'PLATFORM_UNSPECIFIED',pluginType:'GEMINI'};
const reasons={DASHER_USER:'此账号属于组织账号，请使用个人订阅账号或配置组织 Cloud Project。',
  INELIGIBLE_ACCOUNT:'Google 判定此账号不具备当前模型服务资格。',NON_USER_ACCOUNT:'此账号不是可用的个人用户账号。',
  RESTRICTED_AGE:'Google 返回账号年龄限制。',RESTRICTED_NETWORK:'Google 返回网络访问限制。',
  UNKNOWN_LOCATION:'Google 无法确认账号所在地区。',UNSUPPORTED_LOCATION:'Google 返回地区不受支持。',
  VALIDATION_REQUIRED:'Google 要求先完成账号验证。',UNKNOWN:'Google 未返回明确的资格失败原因。'};
export class GoogleSetupError extends Error {
  constructor(code,diagnostic={}) {
    super(code==='PROJECT_REQUIRED' ? 'Google 授权已保存，但该账号需要填写 Cloud Project ID 后重试。' :
      code==='INELIGIBLE' ? [...new Set(diagnostic.reasons.map(reason=>reasons[reason] || reasons.UNKNOWN))].join(' ') :
      code==='VALIDATION_REQUIRED' ? reasons.VALIDATION_REQUIRED :
      code==='PROJECT_INVALID' ? '请填写项目 ID（如 my-project-123），不要填写纯数字项目编号。' :
      code==='ONBOARD_FAILED' ? 'Google 授权已保存，模型项目开通失败，请检查账号权限后重试。' :
      'Google 授权已保存，模型权限检查失败，请检查网络和账号后重试。');
    this.name='GoogleSetupError';this.setupCode=code;this.diagnostic=diagnostic;
  }
}
export function validateGoogleProject(value) {
  if(value===undefined || value==='')return undefined;
  if(typeof value!=='string' || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(value))throw new GoogleSetupError('PROJECT_INVALID');
  return value;
}
function verificationUrl(value) {
  try {const url=new URL(value);return url.protocol==='https:' && !url.username && !url.password && !url.port &&
    ['accounts.google.com','myaccount.google.com'].includes(url.hostname) ? url.href : '';}catch{return '';}
}
export async function resolveGoogleProject(access,{projectId,signal,onProgress=()=>{},onDiagnostic=()=>{},fetchImpl=globalThis.fetch}={}) {
  projectId=validateGoogleProject(projectId);
  const deadline=signal ? AbortSignal.any([signal,AbortSignal.timeout(120000)]) : AbortSignal.timeout(120000);
  const headers={authorization:`Bearer ${access}`,'content-type':'application/json','user-agent':'google-api-nodejs-client/9.15.1','x-goog-api-client':'gl-node/22.17.0'};
  const request=async(action,body)=>{
    const response=await fetchImpl(`${endpoint}${body ? ':' : '/'}${action}`,{method:body?'POST':'GET',headers,signal:deadline,...(body?{body:JSON.stringify(body)}:{})});
    if(!response.ok)throw new GoogleSetupError(action==='loadCodeAssist'?'LOAD_FAILED':'ONBOARD_FAILED');
    const text=await response.text();if(text.length>256*1024)throw new GoogleSetupError('LOAD_FAILED');
    return JSON.parse(text);
  };
  onProgress('检查模型权限和项目');
  const data=await request('loadCodeAssist',{cloudaicompanionProject:projectId,metadata:{...metadata,duetProject:projectId}});
  if(!data || typeof data!=='object')throw new GoogleSetupError('LOAD_FAILED');
  const diagnostic={currentTier:typeof data.currentTier?.id==='string'?data.currentTier.id.slice(0,80):'',
    paidTier:typeof data.paidTier?.id==='string'?data.paidTier.id.slice(0,80):'',managedProjectPresent:!!data.cloudaicompanionProject,
    reasons:(Array.isArray(data.ineligibleTiers)?data.ineligibleTiers:[]).slice(0,10).map(t=>Object.hasOwn(reasons,t.reasonCode)?t.reasonCode:'UNKNOWN')};
  onDiagnostic(diagnostic);
  if(!data.currentTier && diagnostic.reasons.includes('VALIDATION_REQUIRED')) {
    diagnostic.validationUrl=verificationUrl(data.ineligibleTiers.find(t=>t.reasonCode==='VALIDATION_REQUIRED')?.validationUrl);
    throw new GoogleSetupError('VALIDATION_REQUIRED',diagnostic);
  }
  if(data.currentTier) {
    if(typeof data.cloudaicompanionProject==='string' && data.cloudaicompanionProject)return data.cloudaicompanionProject;
    if(projectId)return projectId;
    throw new GoogleSetupError(diagnostic.reasons.length?'INELIGIBLE':'PROJECT_REQUIRED',diagnostic);
  }
  // Like current Gemini CLI, let Google onboard its default tier even without a user project.
  // Pi 0.60.0 rejected non-free default tiers before trying this request.
  const tier=(Array.isArray(data.allowedTiers)?data.allowedTiers:[]).find(t=>t.isDefault)?.id || 'legacy-tier';
  onProgress('开通模型项目');
  let operation=await request('onboardUser',{tierId:tier,...(tier==='free-tier'?{metadata}:{cloudaicompanionProject:projectId,metadata:{...metadata,duetProject:projectId}})});
  while(!operation.done && operation.name) {
    if(typeof operation.name!=='string' || !/^operations\/[a-zA-Z0-9._/-]+$/.test(operation.name) || operation.name.includes('..'))throw new GoogleSetupError('ONBOARD_FAILED');
    await delay(5000,undefined,{signal:deadline});operation=await request(operation.name);
  }
  const result=operation.response?.cloudaicompanionProject?.id;
  if(operation.error)throw new GoogleSetupError('ONBOARD_FAILED',diagnostic);
  if(typeof result==='string' && result)return result;
  if(projectId)return projectId;
  throw new GoogleSetupError(diagnostic.reasons.length?'INELIGIBLE':'PROJECT_REQUIRED',diagnostic);
}
