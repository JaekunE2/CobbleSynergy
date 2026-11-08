"""Compatibility entry point. Player updater is distributed by CobbleLeague."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).with_name('package_release.py')),run_name='__main__')
