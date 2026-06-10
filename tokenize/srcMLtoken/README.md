# srcMLtoken

`srcMLtoken` transcodes the output of srcML ([www.srcml.org](www.srcml.org)) to a tokenized view that can be used by Cregit.

## Requirements

`srcml` must be installed and in the PATH ([www.srcml.org](www.srcml.org))

## How to use

The input of `srml2token` is the output of `srcml`. `srml2token` takes no parameters, and it reads its input from _stdin_ and writes to _stdout_.

```sh
srcml -l C --position <filename> | srcml2token
```

## How to build

It requires `xerces` to be installed. Simply run:

```sh
make
```

## TODO

- Create a `cmake` configuration file.

## License

The code is mostly derived from the examples of Xerces, hence it is under the
Apache-2.0

Dependencies:
  - `xerces`: Apache-2.0
