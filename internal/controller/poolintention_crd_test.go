package controller

import (
	"os"
	"testing"

	"sigs.k8s.io/yaml"
)

// A worker pool is cattle: the MachineDeployment names its machines and a worker registers on no
// endpoint, so its intent has neither a roster nor a control-plane endpoint, and the CRD must admit
// it. A control-plane pool still needs both, and the apiserver refuses it without them. Read off
// the generated CRDs, the files rke2lab stages, rather than the Go markers that produce them.
func TestTheCrdsRequireRosterAndEndpointOfAControlPlanePoolOnly(t *testing.T) {
	const rule = "self.role != 'control-plane' || (has(self.nodes) && size(self.nodes) > 0 && has(self.controlPlaneEndpoint))"
	for _, file := range []string{
		"cluster.seedmatic.io_poolintentions.yaml",
		"cluster.seedmatic.io_pooladoptions.yaml",
	} {
		raw, err := os.ReadFile("../../config/crd/" + file)
		if err != nil {
			t.Fatalf("%s: %v", file, err)
		}
		var crd struct {
			Spec struct {
				Versions []struct {
					Schema struct {
						OpenAPIV3Schema struct {
							Properties map[string]struct {
								Required    []string `json:"required"`
								Validations []struct {
									Rule string `json:"rule"`
								} `json:"x-kubernetes-validations"`
							} `json:"properties"`
						} `json:"openAPIV3Schema"`
					} `json:"schema"`
				} `json:"versions"`
			} `json:"spec"`
		}
		if err := yaml.Unmarshal(raw, &crd); err != nil {
			t.Fatalf("%s: %v", file, err)
		}
		spec := crd.Spec.Versions[0].Schema.OpenAPIV3Schema.Properties["spec"]
		for _, field := range spec.Required {
			if field == "nodes" || field == "controlPlaneEndpoint" {
				t.Errorf("%s: spec.%s is required of every pool, a worker's included", file, field)
			}
		}
		found := false
		for _, v := range spec.Validations {
			found = found || v.Rule == rule
		}
		if !found {
			t.Errorf("%s: no rule keeps a control-plane pool's roster and endpoint required (have %v)",
				file, spec.Validations)
		}
	}
}
