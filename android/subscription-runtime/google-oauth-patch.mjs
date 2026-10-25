// Reproducible extension of the locked Pi 0.60.0 OAuth adapter. No node_modules edits.
export function patchGoogleOAuth(source) {
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw Error('Locked Google OAuth adapter layout changed');
    source = source.replace(before, after);
  };
  replace('loginGeminiCli(onAuth, onProgress, onManualCodeInput) {', 'loginGeminiCli(onAuth, onProgress, onManualCodeInput, onCredentials, resolveProject) {');
  replace('        // Get user email', `        // Save the successful grant before optional identity/project setup can fail.
        if (!tokenData.access_token || !Number.isFinite(tokenData.expires_in)) throw Error("Invalid Google token response");
        await onCredentials?.({type:"oauth",access:tokenData.access_token,refresh:tokenData.refresh_token,
            expires:Date.now()+tokenData.expires_in*1000-5*60*1000,projectId:"",setupPending:true});
        // Get user email`);
  replace('const projectId = await discoverProject(tokenData.access_token, onProgress);', 'const projectId = await (resolveProject || discoverProject)(tokenData.access_token, onProgress);');
  return source;
}
