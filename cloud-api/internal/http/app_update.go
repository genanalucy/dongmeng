package httpapi

import (
	"net/http"

	"github.com/dngmeng/cloud-api/internal/config"
)

// appUpdate is public, immutable release metadata. The APK itself is hosted by
// the configured HTTPS distribution URL, so application servers never proxy
// large binary downloads.
func appUpdate(update config.AppUpdate) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		// Release metadata can be cached briefly, but not indefinitely: clients
		// must eventually observe a rollback or emergency update.
		w.Header().Set("Cache-Control", "public, max-age=300")
		if !update.Enabled {
			writeJSON(w, http.StatusOK, map[string]bool{"available": false})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"available":     true,
			"package_name":  update.PackageName,
			"version_code":  update.VersionCode,
			"version_name":  update.VersionName,
			"apk_url":       update.APKURL,
			"apk_sha256":    update.APKSHA256,
			"release_notes": update.ReleaseNotes,
			"force_update":  update.ForceUpdate,
		})
	}
}
