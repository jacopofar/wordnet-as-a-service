# wordnet-as-a-service
This application exposes a few WordNet functions with a simple REST interface.
In particular, can be used from a browser or as a text annotator.

The access to the WordNet database is done using [extJWNL](https://github.com/extjwnl/extjwnl)

How to use
-----

Run the service by cloning it and use `mvn compile && mvn exec:java` or using Docker

    docker run -p 5679:5679 jacopofar/wordnet-as-a-service

__Retrieve hypernyms of a word:__

    curl http://localhost:5679/hypernyms/1/fork

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

    curl http://localhost:5679/hyponyms/1/tool

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

     curl http://localhost:5679/holonyms/1/France

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

Just change the first of the URL with one of: _holonyms, entailments, substance_meronyms, hyponyms, antonyms, synonyms, substance_holonyms, meronyms, causess or hypernyms_.

The number in the URL is the senses to be considered when retrieving the word synsets. Increasing it will lead to more results but often seemingly _wrong_ ones.

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

this format is suitable to be used as an HTTP annotator for [Fleximatcher](https://github.com/jacopofar/fleximatcher-web-interface)