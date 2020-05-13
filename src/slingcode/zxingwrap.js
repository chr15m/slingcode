module.exports = {
  "lib": window["RTCPeerConnection"] ? require("@zxing/library") : null,
};
