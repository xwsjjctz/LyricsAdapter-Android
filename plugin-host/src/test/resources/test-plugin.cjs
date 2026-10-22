// A legacy music plugin whose invoke() actions exercise the compat layer.
exports.createPlugin = function createPlugin(host) {
  const crypto = require('node:crypto');
  const zlib = require('zlib');

  const actions = {
    async crypto() {
      const cbc = crypto.createCipheriv('aes-128-cbc', Buffer.from('0CoJUm6Qyw8W8jud'), Buffer.from('0102030405060708'));
      const ecb = crypto.createCipheriv('aes-128-ecb', Buffer.from('e82ckenh8dichen8'), null);
      return {
        cbc: Buffer.concat([cbc.update(JSON.stringify({ s: '晴天', limit: 5 }), 'utf8'), cbc.final()]).toString('base64'),
        ecb: ecb.update('hello lyrics', 'utf8', 'hex') + ecb.final('hex'),
        md5: crypto.createHash('md5').update('hello').digest('hex'),
        hmac: crypto.createHmac('sha256', 'key').update('The quick brown fox jumps over the lazy dog').digest('hex'),
        uuid: crypto.randomUUID(),
        randomLength: crypto.randomBytes(24).length,
      };
    },

    async zlib(deflated) {
      const text = zlib.inflateSync(Buffer.from(deflated, 'base64')).toString('utf8');
      const streamed = await new Promise((resolve, reject) => {
        const chunks = [];
        const deflate = zlib.createDeflate();
        deflate.on('data', (chunk) => chunks.push(chunk));
        deflate.on('end', () => resolve(zlib.inflateSync(Buffer.concat(chunks)).toString('utf8')));
        deflate.on('error', reject);
        deflate.end(Buffer.from(text));
      });
      return { text, streamed };
    },

    async fetch(url) {
      const response = await fetch(url, { redirect: 'manual', headers: { Cookie: 'uin=1' } });
      return {
        status: response.status,
        cookies: response.headers.getSetCookie(),
        location: response.headers.get('location'),
        body: Buffer.from(await response.arrayBuffer()).toString('base64'),
      };
    },

    async timers() {
      const pending = setTimeout(() => {
        throw new Error('a cleared timer fired');
      }, 60000);
      clearTimeout(pending);
      await new Promise((resolve) => setTimeout(resolve, 20));
      return 'done';
    },

    async web() {
      const url = new URL('search?q=%E6%99%B4', 'https://music.163.com/api/');
      return {
        href: url.href,
        q: url.searchParams.get('q'),
        params: new URLSearchParams({ s: '晴天', type: '1' }).toString(),
        encoded: Array.from(new TextEncoder().encode('晴')),
        decoded: new TextDecoder().decode(new Uint8Array([0xe6, 0x99, 0xb4])),
        base64: btoa('ok'),
      };
    },

    async secrets(value) {
      host.writeSecrets({ cookie: value });
      return host.readSecret('cookie');
    },

    async fail() {
      throw new Error('upstream said no');
    },
  };

  return {
    provider: {
      id: 'test-source',
      searchMusic: async () => [{ songmid: '1', songname: 'Song', singer: [{ name: 'Singer' }] }],
      getRecommendedSongs: async () => [],
      getMusicUrl: async () => ({ url: 'https://cdn.example.com/a.mp3', bitrate: '320', quality: '320' }),
      getLyrics: async () => null,
      getPlaylists: async () => [],
      getPlaylistSongs: async () => [],
      getCoverUrl: (song) => `https://img.example.com/${song.songmid}.jpg`,
      getRawCookie: () => host.readSecret('cookie'),
      hasCookie: () => Boolean(host.readSecret('cookie')),
      requiresCookie: () => false,
    },
    async validateCookie(cookie) {
      return { valid: cookie === 'ok' };
    },
    async invoke(action, args) {
      const run = actions[action];
      if (!run) throw new Error(`Unsupported action: ${action}`);
      return run(...args);
    },
    streamHeaders(cookie) {
      return { Referer: 'https://example.com', Cookie: cookie };
    },
  };
};
