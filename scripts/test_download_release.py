import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('download_release', ROOT/'scripts/prepare-download-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DownloadReleaseTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ROOT/'site/download/releases.json').read_text())

    def test_rejects_channel_mix_path_traversal_and_unsafe_origin(self):
        for origin in ['http://example.test', 'https://user:secret@example.test', 'https://example.test/path', 'https://example.test/#x']:
            with self.assertRaises(ValueError): module.validate_manifest(self.manifest, origin)
        self.manifest['channels']['debug']['package_name'] = 'com.verba.interpretation'
        with self.assertRaises(ValueError): module.validate_manifest(self.manifest, 'https://example.test')
        self.manifest['channels']['debug']['package_name'] = 'com.verba.interpretation.debug'
        self.manifest['channels']['debug']['file_name'] = '../escape.apk'
        with self.assertRaises(ValueError): module.validate_manifest(self.manifest, 'https://example.test')

    def test_build_produces_page_and_isolated_ota_from_same_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); inputs=root/'inputs';inputs.mkdir()
            for channel in ['production','debug']: (inputs/(channel+'.apk')).write_bytes(channel.encode())
            self.manifest['channels']['debug']['notes']='<script>not markup</script>'
            with patch.object(module,'verify_apk') as verify:
                module.build_snapshot(self.manifest, inputs, root/'out', 'https://example.test', root,
                                      ROOT/'site/download/index.template.html')
                self.assertEqual(verify.call_count,2)
            out=root/'out/snapshot'
            page=(out/'index.html').read_text()
            self.assertIn('&lt;script&gt;not markup&lt;/script&gt;',page)
            for channel in ['production','debug']:
                update=json.loads((out/(channel+'.json')).read_text())
                item=self.manifest['channels'][channel]
                self.assertEqual(update['apk_sha256'],item['sha256'])
                self.assertEqual(update['package_name'],item['package_name'])
                self.assertEqual(update['apk_url'],'https://example.test/downloads/'+item['file_name'])
                self.assertIn(item['sha256'],page)

    def test_route_patch_keeps_other_services_and_separates_channels(self):
        spec=importlib.util.spec_from_file_location('download_routes',ROOT/'scripts/install-download-routes.py')
        routes=importlib.util.module_from_spec(spec);spec.loader.exec_module(routes)
        source='47-129-170-16.sslip.io {\n\thandle /api/v1/app-update {\n\t\troot * /opt/dngmeng/ota\n\t\trewrite * /debug-v26.json\n\t\tfile_server\n\t}\n}\nyszn.nlplayer.org {\n handle /api/* { reverse_proxy 127.0.0.1:8080 }\n}\nother.example { respond "unchanged" }\n'
        result=routes.patch(source,(ROOT/'site/download/Caddyfile.snippet').read_text())
        self.assertIn('other.example { respond "unchanged" }',result)
        self.assertEqual(result.count('handle @downloadPage'),2)
        self.assertIn('rewrite * /production.json',result)
        self.assertIn('rewrite * /debug.json',result)
        with self.assertRaises(ValueError): routes.patch(result,(ROOT/'site/download/Caddyfile.snippet').read_text())

    def test_failed_verification_never_creates_deployable_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            with patch.object(module,'verify_apk',side_effect=ValueError('bad signature')):
                with self.assertRaises(ValueError):
                    module.build_snapshot(self.manifest,root,root/'out','https://example.test',root,ROOT/'site/download/index.template.html')
            self.assertFalse((root/'out').exists())


if __name__=='__main__': unittest.main()
