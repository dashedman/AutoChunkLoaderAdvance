### Simple tool to scan generated chunks from files and count observers in it

## Pre-install

Install uv to your system [here](https://docs.astral.sh/uv/getting-started/installation/)

## Run via Python

```commandline
uv run region_scan.py /path/to/world/dir
```

## Compile

```commandline
uv run nuitka --mode=onefile .\region_scan.py
```

or with specifed output path

```commandline
uv run nuitka --mode=onefile --output-filename="/path/to/output" .\region_scan.py
```

Case: `--output-filename="../../resources/region_scan.exe"` to put it in Java resources on Windows.
Case: `--output-filename="../../resources/region_scan.bin"` to put it in Java resources on Linux.

#### Run as compiled executable

```commandline
region_scan.exe /path/to/world/dir
```

### Output

- In **stdout** it will be lines of chunks coords and counted observers.
- In **stderr** it will be scan progress bar.

