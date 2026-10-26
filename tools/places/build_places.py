"""
Builds app/src/main/assets/places/places.tsv, the offline place list photo search matches
place names against, from GeoNames (geonames.org, CC BY 4.0):

    curl -O https://download.geonames.org/export/dump/cities5000.zip && unzip cities5000.zip
    curl -O https://download.geonames.org/export/dump/admin1CodesASCII.txt
    curl -O https://download.geonames.org/export/dump/admin2Codes.txt
    curl -O https://download.geonames.org/export/dump/countryInfo.txt
    python3 build_places.py <that folder> ../../app/src/main/assets/places/places.tsv

Lines: P <tab> name <tab> other names (comma) <tab> lat <tab> lon <tab> country <tab> admin1 <tab> population
<tab> admin2 for places, then R <tab> country.admin1 <tab> region name, D <tab> country.admin1.admin2 <tab>
district name (only districts with a place in the list), and C <tab> country <tab> country name.
Other names are the Latin-letter ones only (Cochin for Kochi, Bangalore for Bengaluru), at most twelve.
"""
import sys, re

src, out = sys.argv[1], sys.argv[2]
latin = re.compile(r"^[A-Za-z][A-Za-z .'-]{1,40}$")
lines = []
districts = set()
for row in open(f"{src}/cities5000.txt", encoding="utf-8"):
    f = row.rstrip("\n").split("\t")
    name, ascii_name, alternates = f[1], f[2], f[3]
    others = []
    for a in [ascii_name] + alternates.split(","):
        a = a.strip()
        if a and latin.match(a) and a.lower() != name.lower() and a.lower() not in (o.lower() for o in others):
            others.append(a)
        if len(others) == 12:
            break
    lines.append("\t".join(["P", name, ",".join(others), f"{float(f[4]):.4f}", f"{float(f[5]):.4f}", f[8], f[10], f[14], f[11]]))
    if f[11]:
        districts.add(f"{f[8]}.{f[10]}.{f[11]}")
for row in open(f"{src}/admin1CodesASCII.txt", encoding="utf-8"):
    code, name, ascii_name, _ = row.rstrip("\n").split("\t")
    lines.append("\t".join(["R", code, ascii_name]))
for row in open(f"{src}/admin2Codes.txt", encoding="utf-8"):
    code, name, ascii_name, _ = row.rstrip("\n").split("\t")
    if code in districts:
        lines.append("\t".join(["D", code, ascii_name]))
for row in open(f"{src}/countryInfo.txt", encoding="utf-8"):
    if row.startswith("#"):
        continue
    f = row.rstrip("\n").split("\t")
    lines.append("\t".join(["C", f[0], f[4]]))
# Plain text: the APK deflates it (to 2.7 MB), and aapt would unpack a .gz asset anyway.
with open(out, "w", encoding="utf-8") as g:
    g.write("\n".join(lines) + "\n")
print(len(lines), "lines")
