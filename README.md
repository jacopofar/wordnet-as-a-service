# wordnet-as-a-service

## Deprecation note

While this application should still work it's not updated since many years and I think this data can be much better handled using projects such as [Wiktextract](https://github.com/tatuylonen/wiktextract)

This repository is archived to reduce noise

------

This application exposes a few WordNet functions with a simple REST interface.
In particular, can be used from a browser or as a text annotator.

The access to the WordNet database is done using [extJWNL](https://github.com/extjwnl/extjwnl)

How to use
-----

Run the service by cloning it and use `mvn compile && mvn exec:java` or using Docker

    docker run -p 5679:5679 jacopofar/wordnet-as-a-service

__Retrieve hypernyms of a word:__

    curl http://localhost:5679/hypernym/1/fork

    [
      {
        "POS": "noun",
        "word": "cutlery"
      },
      {
        "POS": "noun",
        "word": "eating utensil"
      },
      {
        "POS": "noun",
        "word": "division"
      },
      {
        "POS": "verb",
        "word": "lift"
      },
      ...

__hyponyms__

    curl http://localhost:5679/hyponym/1/tool

    [
      {
        "POS": "noun",
        "word": "abrader"
      },
      {
        "POS": "noun",
        "word": "abradant"
      },
      {
        "POS": "noun",
        "word": "bender"
      },
      ...

__holonyms__

     curl http://localhost:5679/holonym/1/France

     [
       {
         "POS": "noun",
         "word": "Europe"
       },
       {
         "POS": "noun",
         "word": "European Union"
       },
       {
         "POS": "noun",
         "word": "EU"
       },
       ...

__synonyms, substance holonyms, meronyms and others__

Just change the first of the URL with one of: _holonym, entailment, substance_meronym, hyponym, antonym, synonym, substance_holonym, meronym, cause or hypernym_.

The number in the URL is the senses to be considered when retrieving the word synsets. Increasing it will lead to more results but often seemingly "wrong" ones.

__word definitions__

Use

    http://localhost:5679/definition/can

to get the glosses:

    [
     {
       "POS": "noun",
       "gloss": "airtight sealed metal container for food or drink or paint etc.",
       "other terms": "[can, tin can, tin]"
     },
     {
       "POS": "noun",
       "gloss": "the quantity contained in a can",
       "other terms": "[can, canful]"
     },
     {
     ...

__As an annotator__

The server can also produce a list of annotations for a given text and pattern:

    curl -X POST -H "Content-Type: application/json" '{"parameter":"pos=n,w=cat","text":"a feline is jumping right here"}' "http://localhost:5679/hypernyms_tagger/1"

will return:

    {
      "annotations": [
        {
          "span_start": 2,
          "span_end": 8
        }
      ]
    }

__Generate a sample__
You can use the same format of the annotator to generate a sample of something matching the word type:

    curl -X POST -H "Content-Type: application/json" -H "Cache-Control: no-cache" -H "Postman-Token: 41610ffd-f6e9-1573-850d-86b7bdcb29af" -d '{"parameter":"pos=n,w=bike"}' "http://localhost:5679/sample/hyponym/4"

will return, for example, _velocipede_.

This format is suitable to be used as an HTTP annotator/generator for [Fleximatcher](https://github.com/jacopofar/fleximatcher-web-interface)

Plurals
=======

The server uses [Evo inflector](https://github.com/atteo/evo-inflector) to educatedly guess English words plurals and match them (so, matching the hyponyms of "animal" will get "cats" as well as "cat")
