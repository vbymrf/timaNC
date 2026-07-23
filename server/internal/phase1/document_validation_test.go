package phase1

import (
	"encoding/json"
	"errors"
	"testing"
)

func TestPrivateMarkupValidation(t *testing.T) {
	raw := json.RawMessage(`{
		"entities":[
			{"secret_ref":"link.target","nodes":[0],"type":"text_link"},
			{"media_id":"00000000-0000-0000-0000-000000000001","secret_ref":"media.key","type":"media"}
		]
	}`)
	canonical, info, err := validateAndCanonicalizeMarkup(raw, 1)
	if err != nil {
		t.Fatal(err)
	}
	if !info.HasMedia || !info.HasSecrets {
		t.Fatalf("markup info = %#v", info)
	}
	expected := `{"entities":[{"nodes":[0],"secret_ref":"link.target","type":"text_link"},` +
		`{"media_id":"00000000-0000-0000-0000-000000000001","secret_ref":"media.key","type":"media"}]}`
	if string(canonical) != expected {
		t.Fatalf("canonical markup = %s", canonical)
	}
}

func TestPrivateMarkupRejectsExecutableAndInvalidReferences(t *testing.T) {
	cases := []json.RawMessage{
		json.RawMessage(`{"entities":[{"type":"text_link","nodes":[0],"href":"javascript:alert(1)"}]}`),
		json.RawMessage(`{"entities":[{"type":"bold","nodes":[1]}]}`),
		json.RawMessage(`{"entities":[{"type":"media","media_id":"00000000-0000-0000-0000-000000000001"}]}`),
		json.RawMessage(`{"onload":"run()"}`),
	}
	for _, raw := range cases {
		if _, _, err := validateAndCanonicalizeMarkup(raw, 1); !errors.Is(err, ErrInvalid) {
			t.Errorf("markup %s error = %v", raw, err)
		}
	}
}

func TestOptionalMarkupEmptyFormsNormalizeToAbsence(t *testing.T) {
	for _, raw := range []json.RawMessage{nil, json.RawMessage(`null`), json.RawMessage(`{}`), json.RawMessage(`[]`)} {
		canonical, info, err := validateAndCanonicalizeMarkup(raw, 0)
		if err != nil || canonical != nil || info.HasMedia || info.HasSecrets || len(info.MediaIDs) != 0 {
			t.Fatalf("markup %q normalized to %q, %#v, %v", raw, canonical, info, err)
		}
	}
}
