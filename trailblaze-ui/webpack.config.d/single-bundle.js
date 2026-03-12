// Force webpack to produce a single JS bundle (no code splitting).
// The Trailblaze report embeds all JS/WASM inline in a self-contained HTML file.
// Split chunks fail to load when opened from S3 signed URLs or as a local file.
const webpack = require('webpack');
config.plugins = config.plugins || [];
config.plugins.push(new webpack.optimize.LimitChunkCountPlugin({
    maxChunks: 1
}));
