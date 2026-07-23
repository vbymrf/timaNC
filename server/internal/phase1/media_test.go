package phase1

import (
	"crypto/sha256"
	"encoding/hex"
	"image"
	"testing"
)

func TestMediaManifestPolicy(t *testing.T) {
	value := []byte("ciphertext")
	digest := sha256.Sum256(value)
	variant := func(name string) MediaVariantInput {
		return MediaVariantInput{
			Name: name, ContentType: "application/octet-stream", Size: int64(len(value)),
			SHA256: hex.EncodeToString(digest[:]),
		}
	}
	private := MediaUploadCreate{
		Kind: "image",
		Variants: []MediaVariantInput{
			variant("thumbnail"), variant("preview"), variant("full"),
		},
	}
	if err := validateMediaCreate(private, true); err != nil {
		t.Fatal(err)
	}
	private.Variants[0].Name = "original"
	if err := validateMediaCreate(private, true); err == nil {
		t.Fatal("original variant was accepted")
	}
	public := MediaUploadCreate{
		Kind: "image",
		Variants: []MediaVariantInput{
			{
				Name: "full", ContentType: "image/png", Size: int64(len(value)),
				SHA256: hex.EncodeToString(digest[:]),
			},
		},
	}
	if err := validateMediaCreate(public, false); err != nil {
		t.Fatal(err)
	}
}

func TestPublicMediaProcessingPrimitives(t *testing.T) {
	if !executableMagic([]byte("MZ executable")) ||
		!executableMagic([]byte{0x7f, 'E', 'L', 'F'}) ||
		executableMagic([]byte{0x89, 'P', 'N', 'G'}) {
		t.Fatal("executable magic policy mismatch")
	}
	source := image.NewRGBA(image.Rect(0, 0, 4000, 2000))
	resized := resizeWithin(source, 320)
	if resized.Bounds().Dx() != 320 || resized.Bounds().Dy() != 160 {
		t.Fatalf("resized bounds = %v", resized.Bounds())
	}
}
