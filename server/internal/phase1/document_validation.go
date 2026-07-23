package phase1

import (
	"bytes"
	"encoding/json"
	"regexp"
	"strings"
)

type markupInfo struct {
	HasMedia   bool
	HasSecrets bool
	MediaIDs   []string
}

var secretRefPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`)

func validateAndCanonicalizeMarkup(
	raw json.RawMessage,
	nodeCount int,
) ([]byte, markupInfo, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) ||
		bytes.Equal(trimmed, []byte("{}")) || bytes.Equal(trimmed, []byte("[]")) {
		return nil, markupInfo{}, nil
	}
	decoder := json.NewDecoder(bytes.NewReader(trimmed))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, markupInfo{}, ErrInvalid
	}
	root, ok := value.(map[string]any)
	if !ok || len(root) == 0 {
		return nil, markupInfo{}, ErrInvalid
	}
	info := markupInfo{}
	secretRefs := map[string]bool{}
	if err := validateMarkupValue(root, nodeCount, 1, &info, secretRefs); err != nil {
		return nil, markupInfo{}, err
	}
	var out bytes.Buffer
	encoder := json.NewEncoder(&out)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(root); err != nil {
		return nil, markupInfo{}, ErrInvalid
	}
	return bytes.TrimSuffix(out.Bytes(), []byte("\n")), info, nil
}

func validateMarkupValue(
	value any,
	nodeCount, depth int,
	info *markupInfo,
	secretRefs map[string]bool,
) error {
	if depth > 10 {
		return ErrInvalid
	}
	switch current := value.(type) {
	case map[string]any:
		typeName, _ := current["type"].(string)
		for key, child := range current {
			lowerKey := strings.ToLower(key)
			if strings.HasPrefix(lowerKey, "on") || lowerKey == "script" ||
				lowerKey == "srcdoc" || lowerKey == "macro" {
				return ErrInvalid
			}
			switch lowerKey {
			case "nodes":
				if err := validateNodeIndexes(child, nodeCount); err != nil {
					return err
				}
			case "secret_ref":
				ref, ok := child.(string)
				if !ok || !secretRefPattern.MatchString(ref) || secretRefs[ref] {
					return ErrInvalid
				}
				secretRefs[ref] = true
				info.HasSecrets = true
			case "media_id":
				id, ok := child.(string)
				if !ok || typeName != "media" {
					return ErrInvalid
				}
				if _, err := uuidBytes(id); err != nil {
					return err
				}
				info.HasMedia = true
				info.MediaIDs = append(info.MediaIDs, id)
			case "href":
				return ErrInvalid
			}
			if err := validateMarkupValue(child, nodeCount, depth+1, info, secretRefs); err != nil {
				return err
			}
		}
		if typeName == "media" &&
			(current["media_id"] == nil || current["secret_ref"] == nil) {
			return ErrInvalid
		}
		if typeName == "text_link" && current["secret_ref"] == nil {
			return ErrInvalid
		}
	case []any:
		for _, child := range current {
			if err := validateMarkupValue(child, nodeCount, depth+1, info, secretRefs); err != nil {
				return err
			}
		}
	case string:
		if executableText(current) {
			return ErrInvalid
		}
	}
	return nil
}

func validateNodeIndexes(value any, nodeCount int) error {
	indexes, ok := value.([]any)
	if !ok || len(indexes) == 0 || nodeCount == 0 {
		return ErrInvalid
	}
	seen := map[int]bool{}
	for _, raw := range indexes {
		number, ok := raw.(json.Number)
		if !ok {
			return ErrInvalid
		}
		index64, err := number.Int64()
		if err != nil || index64 < 0 || index64 >= int64(nodeCount) || seen[int(index64)] {
			return ErrInvalid
		}
		seen[int(index64)] = true
	}
	return nil
}

func executableString(value any) bool {
	text, ok := value.(string)
	return !ok || executableText(text)
}

func executableText(value string) bool {
	normalized := strings.ToLower(strings.TrimSpace(value))
	return strings.HasPrefix(normalized, "javascript:") ||
		strings.HasPrefix(normalized, "vbscript:") ||
		strings.HasPrefix(normalized, "data:text/html") ||
		strings.Contains(normalized, "<script") ||
		strings.Contains(normalized, "<iframe") ||
		strings.Contains(normalized, "<object") ||
		strings.Contains(normalized, "<embed")
}
