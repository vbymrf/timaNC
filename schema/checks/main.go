package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/dlclark/regexp2"
	"github.com/santhosh-tekuri/jsonschema/v6"
	"gopkg.in/yaml.v3"
)

const schemaBase = "https://schemas.tima.example/json/"

func main() {
	root, err := filepath.Abs(filepath.Join(".."))
	check(err)
	checkJSONSchemas(root)
	checkOpenAPI(root)
	fmt.Println("JSON Schema fixtures and OpenAPI references are valid.")
}

func checkJSONSchemas(root string) {
	compiler := jsonschema.NewCompiler()
	compiler.UseRegexpEngine(compileECMAScript)
	jsonRoot := filepath.Join(root, "json")
	for _, name := range []string{
		"document-v2.schema.json",
		"private-document-envelope.schema.json",
		"markup.schema.json",
		"metadata.schema.json",
		"entity.schema.json",
	} {
		data, err := os.ReadFile(filepath.Join(jsonRoot, name))
		check(err)
		var resource any
		check(json.Unmarshal(data, &resource))
		check(compiler.AddResource(schemaBase+name, resource))
	}
	document, err := compiler.Compile(schemaBase + "document-v2.schema.json")
	check(err)
	envelope, err := compiler.Compile(schemaBase + "private-document-envelope.schema.json")
	check(err)

	for _, expectation := range []string{"positive", "negative"} {
		files, err := filepath.Glob(filepath.Join(jsonRoot, "fixtures", expectation, "*.json"))
		check(err)
		for _, file := range files {
			var value any
			data, err := os.ReadFile(file)
			check(err)
			check(json.Unmarshal(data, &value))
			target := document
			if object, ok := value.(map[string]any); ok {
				if _, isEnvelope := object["protocol_version"]; isEnvelope {
					target = envelope
				}
			}
			err = target.Validate(value)
			if expectation == "positive" && err != nil {
				panic(fmt.Errorf("%s must be valid: %w", file, err))
			}
			if expectation == "negative" && err == nil {
				panic(fmt.Errorf("%s must be rejected", file))
			}
		}
	}
}

type ecmaRegexp regexp2.Regexp

func (re *ecmaRegexp) MatchString(value string) bool {
	matched, err := (*regexp2.Regexp)(re).MatchString(value)
	return err == nil && matched
}

func (re *ecmaRegexp) String() string {
	return (*regexp2.Regexp)(re).String()
}

func compileECMAScript(pattern string) (jsonschema.Regexp, error) {
	re, err := regexp2.Compile(pattern, regexp2.ECMAScript)
	if err != nil {
		return nil, err
	}
	return (*ecmaRegexp)(re), nil
}

func checkOpenAPI(root string) {
	specPath := filepath.Join(root, "openapi", "client-api.yaml")
	data, err := os.ReadFile(specPath)
	check(err)
	var spec any
	check(yaml.Unmarshal(data, &spec))
	resolveRefs(spec, spec, filepath.Dir(specPath))

	doc := spec.(map[string]any)
	paths := doc["paths"].(map[string]any)
	operations := 0
	for path, raw := range paths {
		item := raw.(map[string]any)
		for method, operation := range item {
			if !isHTTPMethod(method) {
				continue
			}
			operations++
			op := operation.(map[string]any)
			if _, ok := op["operationId"]; !ok {
				panic(fmt.Errorf("%s %s has no operationId", strings.ToUpper(method), path))
			}
			if _, ok := op["x-tima-implementation-phase"]; !ok {
				panic(fmt.Errorf("%s %s has no implementation phase", strings.ToUpper(method), path))
			}
		}
	}
	if operations != 149 {
		panic(fmt.Errorf("expected 149 Client API operations, got %d", operations))
	}
}

func resolveRefs(node, root any, base string) {
	switch value := node.(type) {
	case map[string]any:
		if raw, ok := value["$ref"].(string); ok {
			target := root
			fragment := ""
			if !strings.HasPrefix(raw, "#") {
				parts := strings.SplitN(raw, "#", 2)
				data, err := os.ReadFile(filepath.Join(base, filepath.FromSlash(parts[0])))
				check(err)
				check(json.Unmarshal(data, &target))
				if len(parts) == 2 {
					fragment = parts[1]
				}
			} else {
				fragment = strings.TrimPrefix(raw, "#")
			}
			if fragment != "" {
				resolvePointer(target, fragment, raw)
			}
		}
		for _, child := range value {
			resolveRefs(child, root, base)
		}
	case []any:
		for _, child := range value {
			resolveRefs(child, root, base)
		}
	}
}

func resolvePointer(node any, pointer, ref string) {
	for _, token := range strings.Split(strings.TrimPrefix(pointer, "/"), "/") {
		token = strings.ReplaceAll(strings.ReplaceAll(token, "~1", "/"), "~0", "~")
		object, ok := node.(map[string]any)
		if !ok {
			panic(fmt.Errorf("invalid JSON pointer in %s", ref))
		}
		node, ok = object[token]
		if !ok {
			panic(fmt.Errorf("unresolved reference %s", ref))
		}
	}
}

func isHTTPMethod(value string) bool {
	switch value {
	case "get", "put", "post", "delete", "patch", "head", "options", "trace":
		return true
	default:
		return false
	}
}

func check(err error) {
	if err != nil {
		panic(err)
	}
}
