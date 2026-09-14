// Command seed-incluster runs the cluster-seeding controller: the in-cluster twin of seed-master.
// It reconciles the Flux-owned intent (ClusterIntention + PoolIntention) adopt-first and owns the
// mirror (ClusterAdoption + PoolAdoption) that adopts running RKE2-on-Incus clusters
// (Pulumi-bootstrapped) into Cluster API by creating the CR-set and the OWNED per-pet Machine +
// concrete LXCMachine (providerID), so CAPRKE2/CAPN bind to the existing instances instead of
// provisioning fresh ones. See docs/architecture/cluster-api/cluster-seeding-controller.adoc.
package main

import (
	"flag"
	"os"

	"k8s.io/apimachinery/pkg/runtime"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
	"github.com/seedmatic/seed-incluster/internal/controller"
)

// version is stamped at build time via -ldflags "-X main.version=...".
var version = "dev"

var (
	scheme   = runtime.NewScheme()
	setupLog = ctrl.Log.WithName("setup")
)

func init() {
	utilruntime.Must(clientgoscheme.AddToScheme(scheme))
	utilruntime.Must(adoptionv1alpha1.AddToScheme(scheme))
}

func main() {
	var metricsAddr string
	var probeAddr string
	var enableLeaderElection bool
	flag.StringVar(&metricsAddr, "metrics-bind-address", ":8080", "The address the metric endpoint binds to.")
	flag.StringVar(&probeAddr, "health-probe-bind-address", ":8081", "The address the probe endpoint binds to.")
	flag.BoolVar(&enableLeaderElection, "leader-elect", false,
		"Enable leader election for controller manager. Ensures only one active controller.")
	opts := zap.Options{Development: true}
	opts.BindFlags(flag.CommandLine)
	flag.Parse()

	ctrl.SetLogger(zap.New(zap.UseFlagOptions(&opts)))
	setupLog.Info("starting seed-incluster", "version", version)

	mgr, err := ctrl.NewManager(ctrl.GetConfigOrDie(), ctrl.Options{
		Scheme:                 scheme,
		Metrics:                metricsserver.Options{BindAddress: metricsAddr},
		HealthProbeBindAddress: probeAddr,
		LeaderElection:         enableLeaderElection,
		LeaderElectionID:       "seed-incluster.cluster.seedmatic.io",
	})
	if err != nil {
		setupLog.Error(err, "unable to start manager")
		os.Exit(1)
	}

	// The 2×2 CR set — {ClusterIntention, PoolIntention} (Flux-owned intent, thin hand-off) ×
	// {ClusterAdoption, PoolAdoption} (controller-owned mirror, the adopt-first work).
	if err := (&controller.ClusterIntentionReconciler{
		Client: mgr.GetClient(),
		Scheme: mgr.GetScheme(),
	}).SetupWithManager(mgr); err != nil {
		setupLog.Error(err, "unable to create controller", "controller", "ClusterIntention")
		os.Exit(1)
	}

	if err := (&controller.PoolIntentionReconciler{
		Client: mgr.GetClient(),
		Scheme: mgr.GetScheme(),
	}).SetupWithManager(mgr); err != nil {
		setupLog.Error(err, "unable to create controller", "controller", "PoolIntention")
		os.Exit(1)
	}

	if err := (&controller.ClusterAdoptionReconciler{
		Client:      mgr.GetClient(),
		Scheme:      mgr.GetScheme(),
		SelfCluster: os.Getenv("SELF_CLUSTER_NAME"),
	}).SetupWithManager(mgr); err != nil {
		setupLog.Error(err, "unable to create controller", "controller", "ClusterAdoption")
		os.Exit(1)
	}

	if err := (&controller.PoolAdoptionReconciler{
		Client: mgr.GetClient(),
		Scheme: mgr.GetScheme(),
	}).SetupWithManager(mgr); err != nil {
		setupLog.Error(err, "unable to create controller", "controller", "PoolAdoption")
		os.Exit(1)
	}

	if err := mgr.AddHealthzCheck("healthz", healthz.Ping); err != nil {
		setupLog.Error(err, "unable to set up health check")
		os.Exit(1)
	}
	if err := mgr.AddReadyzCheck("readyz", healthz.Ping); err != nil {
		setupLog.Error(err, "unable to set up ready check")
		os.Exit(1)
	}

	setupLog.Info("starting manager")
	if err := mgr.Start(ctrl.SetupSignalHandler()); err != nil {
		setupLog.Error(err, "problem running manager")
		os.Exit(1)
	}
}
