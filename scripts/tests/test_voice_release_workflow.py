"""Check that fork release builds cannot enable the upstream voice installer."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class VoiceReleaseWorkflowTest(unittest.TestCase):
    def run_pin_step(self, server, repository, sha="not-a-release-sha"):
        lines = (ROOT / ".github/workflows/release.yml").read_text().splitlines()
        start = lines.index("      - name: Verify voice plugin release pin")
        start = lines.index("        run: |", start) + 1
        end = next(i for i in range(start, len(lines)) if lines[i].startswith("      - name:"))
        script = "\n".join(line[10:] if line.startswith("          ") else line for line in lines[start:end])
        with tempfile.TemporaryDirectory() as tmp:
            env_file = Path(tmp) / "environment"
            env_file.touch()
            env = {**os.environ, "GITHUB_SERVER_URL": server, "GITHUB_REPOSITORY": repository,
                   "GITHUB_SHA": sha, "GITHUB_ENV": str(env_file)}
            result = subprocess.run(["bash", "-c", script], cwd=ROOT, env=env, capture_output=True, text=True)
            return result, env_file.read_text()

    def test_fork_release_disables_automatic_voice_installation(self):
        result, output = self.run_pin_step("https://github.com", "Quinchu/kyllene")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "VOICE_PLUGIN_REVISION=\n")

    def test_other_github_server_also_disables_installer(self):
        result, output = self.run_pin_step("https://example.test", "Giton22/kyllene")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "VOICE_PLUGIN_REVISION=\n")

    def test_upstream_releases_still_reject_invalid_pin(self):
        result, output = self.run_pin_step("https://github.com", "Giton22/kyllene")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, "")
        self.assertIn("Invalid release SHA", result.stdout)


if __name__ == "__main__":
    unittest.main()
