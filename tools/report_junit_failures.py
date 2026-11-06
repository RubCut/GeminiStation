#!/usr/bin/env python3
"""Print failed Gradle/JUnit cases when CI cannot expose the full Gradle log."""
from pathlib import Path
import xml.etree.ElementTree as ET

for report in Path('.').glob('*/build/test-results/testDebugUnitTest/*.xml'):
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError):
        continue
    for case in root.iter('testcase'):
        for problem in list(case.findall('failure')) + list(case.findall('error')):
            print(f"FAILED {case.get('classname')}.{case.get('name')}: {problem.get('message', '')}")
            if problem.text:
                print(problem.text[:4000])
