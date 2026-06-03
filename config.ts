// You must have the PUBLIC_OAUTH2_CLIENT application flag set.
export const CLIENT_ID = '<application_id>';
export const SCOPE = ['openid', 'sdk.social_layer_presence'].join(' ');

// Explanation:
// If the application's redirect URL is set to http://127.0.0.1/callback or http://localhost/callback,
// then REDIRECT_URI can use a custom port without needing to specify the exact port in advance.
//
// By the way, on Android it must be "discord-<application_id>:/authorize/callback" by default,
// following the SDK convention.
export const REDIRECT_URI = 'http://127.0.0.1/callback';