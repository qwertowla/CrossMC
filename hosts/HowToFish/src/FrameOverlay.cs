using CrossMC.Bridge;
using UnityEngine;

namespace CrossMC.HowToFish
{
    /// <summary>
    /// Draws the newest Minecraft frame as a screen rectangle. Runs on the Unity main thread
    /// (Update/OnGUI); the shared-memory read is plain memory access.
    /// </summary>
    public sealed class FrameOverlay : MonoBehaviour
    {
        private BridgeMemory _memory;
        private HostConfig _config;
        private Texture2D _texture;
        private byte[] _pixels;
        private bool _hasFrame;

        public void Init(BridgeMemory memory, HostConfig config)
        {
            _memory = memory;
            _config = config;
        }

        private void Update()
        {
            if (_memory == null)
            {
                return;
            }

            int slot = _memory.Acquire();

            if (slot < 0)
            {
                return;
            }

            int width = _memory.SlotWidth(slot);
            int height = _memory.SlotHeight(slot);
            int stride = _memory.SlotStride(slot);

            if (width <= 0 || height <= 0)
            {
                return;
            }

            int need = stride * height;

            if (_pixels == null || _pixels.Length != need || _texture == null
                    || _texture.width != width || _texture.height != height)
            {
                _pixels = new byte[need];
                _texture = new Texture2D(width, height, TextureFormat.BGRA32, false);
            }

            // Rows arrive bottom-up (OpenGL), which matches Unity's texture origin.
            _memory.ReadPixels(slot, _pixels);
            _texture.LoadRawTextureData(_pixels);
            _texture.Apply(false);
            _hasFrame = true;
        }

        private void OnGUI()
        {
            if (!_hasFrame || _texture == null || _config == null)
            {
                return;
            }

            var rect = new Rect(
                    Screen.width * _config.OverlayX,
                    Screen.height * _config.OverlayY,
                    Screen.width * _config.OverlayWidth,
                    Screen.height * _config.OverlayHeight);
            GUI.DrawTexture(rect, _texture, ScaleMode.ScaleToFit, false);
        }

        private void OnDestroy()
        {
            if (_texture != null)
            {
                Destroy(_texture);
            }
        }
    }
}
