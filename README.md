This repo contains files for testing LLMs capabilities in analyzing code and graphs (.dot)-files.
The java files contains vulnerabilities on purpose and is only for testing.

The .dot files were created using Joern.
This is how they were created after after following the joern download steps:
1. Stand in this folder : ~/bin/joern/joern-cli
2. ./joern-parse /path-to-file/ -o cpg.bin
3. ./joern-export cpg.bin --repr=all --format=dot --out=pdg_mapp
4. cp -v pdg_mapp/export.dot /path-to-desktop/(filename)  --> (For easier access)




