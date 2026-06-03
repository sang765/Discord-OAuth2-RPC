import express from 'express';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import {
	GatewayClient,
	GatewayOp,
	RichPresence,
	SpotifyRPC,
	CustomStatus,
	Util,
} from './src';
import { API } from './src';
import { CLIENT_ID, REDIRECT_URI } from './config';
import { TokenResponse } from './src';
import { DEFAULTS } from './src';

const app = express();
let PORT = 0;

const getRedirectURI = () => {
	const redirectUri = new URL(REDIRECT_URI);
	// Set port
	redirectUri.port = String(PORT);
	return redirectUri.toString();
};

const mapState = new Map<string, string>(); // state -> code_verifier
const mapUser = new Map<string, TokenResponse>(); // user_id -> oauth2_data

const buildState = () => randomUUID();

const gateway = new GatewayClient();
const rest = new API();

// Note: This demo uses Express to create a simple server with two endpoints:
// 1. GET /: Redirects the user to Discord's OAuth2 authorization page.
// 2. GET /callback: Handles the OAuth callback, authenticates the user, and initializes RPC.

// On Android, a custom URI scheme must be registered using the format
// "discord-<application_id>:/authorize/callback".
// Query parameters from the callback URL can then be processed as usual.

app.get('/', (_req, res) => {
	// Build OAuth2 URL for client-side authentication flow:
	const url = new URL('https://discord.com/oauth2/authorize');
	url.searchParams.set('client_id', CLIENT_ID);
	url.searchParams.set('redirect_uri', getRedirectURI());
	url.searchParams.set('response_type', 'code');
	url.searchParams.set('scope', 'identify');
	// Generate code_challenge
	const codeVerifier = randomBytes(32).toString('base64url');
	const codeChallenge = createHash('sha256')
		.update(codeVerifier, 'utf8')
		.digest('base64url');
	url.searchParams.set('code_challenge', codeChallenge);
	url.searchParams.set('code_method', 'S256');
	// Generate and store state for CSRF protection:
	const state = buildState();
	mapState.set(state, codeVerifier);
	url.searchParams.set('state', state);
	res.redirect(url.toString());
});

app.get('/callback', async (req, res) => {
	// Parse query parameters:
	// - if ok:
	//  -> code & state
	// - if error:
	//  -> error & error_description & state
	const { code, state, error, error_description } = req.query;
	if (typeof state !== 'string' || !mapState.has(state)) {
		return res.status(400).send('Invalid or missing state parameter');
	}
	const codeVerifier = mapState.get(state)!; // Retrieve code_verifier using state
	mapState.delete(state); // Consume state
	if (error) {
		return res
			.status(400)
			.send(`OAuth2 Error: ${error} - ${error_description}`);
	}
	if (typeof code !== 'string') {
		return res.status(400).send('Missing code parameter');
	}
	// Exchange code for token and fetch user info:
	const resp = await rest.api.oauth2.token.post({
		body: new URLSearchParams({
			client_id: CLIENT_ID,
			grant_type: 'authorization_code',
			code: code,
			code_verifier: codeVerifier,
			redirect_uri: getRedirectURI(),
		}),
	});
	// Expected response: { access_token, token_type, expires_in, refresh_token, scope }
	if (!resp.ok) {
		const text = await resp.text();
		return res
			.status(500)
			.send(`Token exchange failed: ${resp.status} - ${text}`);
	}
	let tokenData = (await resp.json()) as TokenResponse;
	// Try refreshing token immediately to verify refresh flow works (optional):
	// tokenData = await Util.refreshToken(
	// 	rest,
	// 	`${tokenData.token_type} ${tokenData.access_token}`,
	// 	CLIENT_ID,
	// 	tokenData,
	// );
	const userResp = await rest.api.users['@me'].get({
		headers: {
			Authorization: `${tokenData.token_type} ${tokenData.access_token}`,
		},
	});
	if (!userResp.ok) {
		const text = await userResp.text();
		return res
			.status(500)
			.send(`Failed to fetch user info: ${userResp.status} - ${text}`);
	}
	const userData = await userResp.json();
	// Store token data by user ID for later use:
	mapUser.set(userData.id, tokenData);
	// Connect to gateway with user's token:
	try {
		await gateway.connect({
			token: `${tokenData.token_type} ${tokenData.access_token}`,
			identify: {
				capabilities: 0,
			},
			gatewayUrl: DEFAULTS.GATEWAY_SDK_URL,
		});
		res.send(userData);
	} catch (err) {
		console.error('Gateway connection failed but REST OK:', err);
		return res.status(500).send({
			error: 'Gateway connection failed',
			details: err,
			rest_response: userData,
		});
	}
});

const server = app.listen(PORT, () => {
	const address = server.address();
	if (address && typeof address !== 'string') {
		PORT = address.port;
		console.log(`Server listening on http://localhost:${PORT}`);
		fetch(`http://localhost:${PORT}/`, {
			redirect: 'manual',
		}).then((res) => console.log(res.headers.get('location')));
	}
});

process.on('unhandledRejection', (reason) => {
	console.error('Unhandled Rejection:', reason);
});

gateway.on('ready', async (ready) => {
	console.log(`Logged in as @${ready.user.username} (${ready.user.id})`);
	const spotifyRPC = new SpotifyRPC(ready.user.id, ready.session_id)
		.setAssetsLargeImage('spotify:ab67616d00001e02768629f8bc5b39b68797d1bb') // Image ID
		.setAssetsSmallImage('spotify:ab6761610000f178049d8aeae802c96c8208f3b7') // Image ID
		.setAssetsLargeText('未来茶屋 (vol.1)') // Album Name
		.setState('Yunomi; Kizuna AI') // Artists
		.setDetails('ロボットハート') // Song name
		.setStartTimestamp(Date.now())
		.setEndTimestamp(Date.now() + 1_000 * (2 * 60 + 56)) // Song length = 2m56s
		.setSongId('667eE4CFfNtJloC6Lvmgrx') // Song ID
		.setAlbumId('6AAmvxoPoDbJAwbatKwMb9') // Album ID
		.setArtistIds('2j00CVYTPx6q9ANbmB2keb', '2nKGmC5Mc13ct02xAY8ccS'); // Artist IDs
	const tokenData = mapUser.get(ready.user.id)!;
	const getExtendURL = await Util.getExternal(
		rest,
		`${tokenData.token_type} ${tokenData.access_token}`,
		CLIENT_ID,
		'https://assets.ppy.sh/beatmaps/1550633/covers/list.jpg', // Required if the image you use is not in Discord
	);
	const status = new RichPresence(ready.session_id)
		.setApplicationId('367827983903490050')
		.setType('PLAYING')
		// .setURL('https://www.youtube.com/watch?v=5icFcPkVzMg')
		.setState('Arcade Game')
		.setName('osu!')
		.setDetails('MariannE - Yooh')
		.setParty({
			max: 8,
			current: 1,
		})
		.setStartTimestamp(Date.now())
		.setAssetsLargeImage(getExtendURL[0].external_asset_path) // https://assets.ppy.sh/beatmaps/1550633/covers/list.jpg
		.setAssetsLargeText('Idle')
		.setAssetsSmallImage('373370493127884800') // https://discord.com/api/v9/oauth2/applications/367827983903490050/assets
		.setAssetsSmallText('click the circles')
		.setPlatform('desktop')
		.addButton(
			'Beatmap',
			'https://osu.ppy.sh/beatmapsets/1391659#osu/2873429',
		);
	// Custom Status
	const custom = new CustomStatus().setEmoji('😋').setState('yum');
	// Set status:
	gateway.send(GatewayOp.PRESENCE_UPDATE, {
		activities: [spotifyRPC.toJSON(), status.toJSON(), custom.toJSON()],
		afk: false,
		since: '0',
		status: 'idle',
	});
});

gateway.on('debug', console.debug);
