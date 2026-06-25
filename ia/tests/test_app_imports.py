import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from main import app


class AppImportTest(unittest.TestCase):
    def test_app_exposes_only_existing_v1_routers(self):
        paths = {route.path for route in app.routes}

        self.assertIn("/api/v1/health/", paths)
        self.assertIn("/api/v1/forecast/", paths)
        self.assertFalse(any("chatbot" in path for path in paths))
        self.assertFalse(any("recommendations" in path for path in paths))
        self.assertFalse(any("whatsapp" in path for path in paths))
        self.assertFalse(any("analytics" in path for path in paths))


if __name__ == "__main__":
    unittest.main()
