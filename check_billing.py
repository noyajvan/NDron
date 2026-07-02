import subprocess, json, sys

cmd = [
    'ssh', 'ubuntu@141.148.244.92',
    'export OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=True; '
    '/home/ubuntu/bin/oci usage-api query '
    '--compartment-id ocid1.tenancy.oc1..aaaaaaaabg2kxrjp6n5tv3gtw2sunx6bgcf34nqj7oqcrrzzhandfauwayvq '
    '--granularity DAILY '
    '--time-usage-started 2026-06-30'
]

result = subprocess.run(cmd, capture_output=True, text=True)
try:
    d = json.loads(result.stdout)
    items = d.get("data", {}).get("items", [])
    if not items:
        print("No usage data found (Free Tier - no charges)")
    for item in items:
        time = item.get("time", "")
        amount = item.get("computedAmount", 0)
        currency = item.get("currency", "USD")
        print(f"{time[:10]:20s} {amount:>10.6f} {currency}")
except Exception as e:
    print(f"Error: {e}")
    print(result.stdout[:1000])
