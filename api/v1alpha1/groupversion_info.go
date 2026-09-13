// Package v1alpha1 contains the ClusterAdoption API (group cluster.seedmatic.io).
// +kubebuilder:object:generate=true
// +groupName=cluster.seedmatic.io
package v1alpha1

import (
	"k8s.io/apimachinery/pkg/runtime/schema"
	"sigs.k8s.io/controller-runtime/pkg/scheme"
)

var (
	// GroupVersion is the group/version for the ClusterAdoption API.
	GroupVersion = schema.GroupVersion{Group: "cluster.seedmatic.io", Version: "v1alpha1"}

	// SchemeBuilder registers the API types into a runtime.Scheme.
	SchemeBuilder = &scheme.Builder{GroupVersion: GroupVersion}

	// AddToScheme adds the ClusterAdoption types to a scheme.
	AddToScheme = SchemeBuilder.AddToScheme
)
