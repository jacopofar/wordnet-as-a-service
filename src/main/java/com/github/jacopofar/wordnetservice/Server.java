package com.github.jacopofar.wordnetservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jacopofar.wordnetservice.messages.Annotation;
import com.github.jacopofar.wordnetservice.messages.AnnotationRequest;
import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.*;
import net.sf.extjwnl.data.list.PointerTargetNode;
import net.sf.extjwnl.data.list.PointerTargetNodeList;
import net.sf.extjwnl.data.list.PointerTargetTree;
import net.sf.extjwnl.dictionary.Dictionary;
import org.atteo.evo.inflector.English;
import org.json.JSONException;
import org.json.JSONObject;
import spark.Response;

import java.util.*;
import java.util.stream.Collectors;

import static spark.Spark.*;

public class Server {
    enum Relationships {HYPERNYM, HYPONYM, ANTONYM, HOLONYM, SYNONYM, CAUSE,  SUBSTANCE_HOLONYM, SUBSTANCE_MERONYM, MERONYM}
    static HashMap<String, Relationships> relNames = new HashMap<>();
    static{
        for(Relationships v:Relationships.values())
            relNames.put(v.name().toLowerCase(),v);
    }
    static Dictionary dictionary;
    public static void main(String[] args) throws JWNLException {

        dictionary = Dictionary.getDefaultResourceInstance();

        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        port(5679);
        System.out.println("Server started at port 5679");


        if(System.getenv("WNAAS_API_KEY") != null){
            String apiKey = System.getenv("WNAAS_API_KEY");
            System.out.println("the apiKey in X-API-KEY will have to be '" + apiKey + "'");
            before((request, response) -> {
                if(!apiKey.equals(request.headers("X-API-KEY"))){
                    System.out.println("refused a " + request.requestMethod() + " to " + request.pathInfo() + " due to a wrong or missing X-API-KEY header. It was " + request.headers("X-API-KEY"));
                    halt(401, "wrong or missing X-API-KEY header");
                }
            });
        }
        else {
            System.out.println("no WNAAS_API_KEY environment variable, any request will be accepted");
        }




        //show exceptions in console and HTTP responses
        exception(Exception.class, (exception, request, response) -> {
            //show the exceptions using stdout
            System.out.println("Exception:");
            exception.printStackTrace(System.out);
            response.status(400);
            JSONObject exc = new JSONObject();
            try {
                exc.put("error",exception.toString());
            } catch (JSONException e) {
                //can never happen...
                e.printStackTrace();
            }
            response.type("application/json; charset=utf-8");
            response.body(exc.toString());
        });

        /**
         * Tag the words in a given relationship with the parameter
         * */
        post("/:tagger_type/:senses", (request, response) -> {
            Relationships reltype = relNames.get(request.params(":tagger_type"));
            if(reltype == null){
                response.status(404);
                return "Unknown lexical relationship type. Known ones:" + Arrays.toString(relNames.keySet().toArray());
            }
            ObjectMapper mapper = new ObjectMapper();
            AnnotationRequest ar = mapper.readValue(request.body(), AnnotationRequest.class);
            if(ar.errorMessages().size() != 0){
                response.status(400);
                return "invalid request body. Errors: " + ar.errorMessages() ;
            }
            Map<String, String> params = getParams(ar.getParameter());
            if(!params.containsKey("w")){
                response.status(400);
                return "No word parameter found, it should be something like w=cat or pos=n,w=cat" ;
            }


            List<POS> accepted_pos = null;
            List<Word> matchWords = new LinkedList<>();
            //the user can provide the POS or not
            if(params.containsKey("pos")){
                String wordType = params.get("pos").toLowerCase();
                if(!wordType.matches("(adjective)|(noun)|(verb)|(adverb)|(adv)|n|v|(adj)")){
                    response.status(400);
                    return "invalid request, the word type has to be adjective, adverb, noun or verb, or adj, adv, n, v. This was " + wordType ;
                }
                accepted_pos = new LinkedList<>();
                if(wordType.startsWith("adv"))
                    accepted_pos.add(POS.ADVERB);
                if(wordType.startsWith("adj"))
                    accepted_pos.add(POS.ADJECTIVE);
                if(wordType.startsWith("v"))
                    accepted_pos.add(POS.VERB);
                if(wordType.startsWith("n"))
                    accepted_pos.add(POS.NOUN);
            }
            matchWords = getRelated(params.get("w"), reltype, accepted_pos, Integer.parseInt(request.params(":senses")));

            List<Annotation> anns = new ArrayList<>();

            String text = ar.getText().toLowerCase();
            Set<String> distinctWords = withPlurals(matchWords.stream().map(w -> w.getLemma()).collect(Collectors.toSet()));

            distinctWords.forEach(l -> {
                int lastIndex=0;
                while(true){
                    int ind = text.indexOf(l, lastIndex);
                    if(ind ==- 1)
                        break;
                    //it's the English WordNet, so we can tokenize easily and ignore substrings
                    if(ind == 0 || !Character.isLetter(text.charAt(ind -1))){
                        //now we know the word is the first of the string or after a non-alphabetic char
                        if(ind + l.length() == text.length() || !Character.isLetter(text.charAt(ind + l.length()))){
                            //and it's also at the end of the text or before a non-alphabetic char
                            anns.add(new Annotation(ind, ind + l.length()));
                        }
                    }
                    lastIndex=ind+l.length();
                }
            });
            return sendAnnotations(anns, response);
        });

        /**
         * Return a sample value for a parameter. Used by Fleximatcher to generate samples for whole patterns
         * */
        post("/sample/:tagger_type/:senses", (request, response) -> {
            Relationships reltype = relNames.get(request.params(":tagger_type"));
            if(reltype == null){
                response.status(404);
                return "Unknown lexical relationship type. Known ones:" + Arrays.toString(relNames.keySet().toArray());
            }
            String parameter = new JSONObject(request.body()).getString("parameter");

            Map<String, String> params = getParams(parameter);
            if(!params.containsKey("w")){
                response.status(400);
                return "No word parameter found, it should be something like w=cat or pos=n,w=cat" ;
            }


            List<POS> accepted_pos = null;
            List<Word> matchWords = new LinkedList<>();
            //the user can provide the POS or not
            if(params.containsKey("pos")){
                String wordType = params.get("pos").toLowerCase();
                if(!wordType.matches("(adjective)|(noun)|(verb)|(adverb)|(adv)|n|v|(adj)")){
                    response.status(400);
                    return "invalid request, the word type has to be adjective, adverb, noun or verb, or adj, adv, n, v. This was " + wordType ;
                }
                accepted_pos = new LinkedList<>();
                if(wordType.startsWith("adv"))
                    accepted_pos.add(POS.ADVERB);
                if(wordType.startsWith("adj"))
                    accepted_pos.add(POS.ADJECTIVE);
                if(wordType.startsWith("v"))
                    accepted_pos.add(POS.VERB);
                if(wordType.startsWith("n"))
                    accepted_pos.add(POS.NOUN);
            }
            matchWords = getRelated(params.get("w"), reltype, accepted_pos, Integer.parseInt(request.params(":senses")));

            Set<String> distinctWords = withPlurals(matchWords.stream().map(w -> w.getLemma()).collect(Collectors.toSet()));
            if(distinctWords.size() == 0)
                return "";
            int elapsing = (int) Math.floor(Math.random() * distinctWords.size());
            for(String w: distinctWords){
                elapsing--;
                if(elapsing <= 0)
                    return w;
            }
            return null;
        });


        /**
         * List the hypernyms/hyponyms/anything else of a given word. It doesn't return plurals, but those are considered when parsing
         * */
        get("/:relationship/:senses/:word", (request, response) -> {
            Relationships reltype = relNames.get(request.params(":relationship").replaceAll("s$",""));
            if(reltype == null){
                response.status(404);
                return "Unknown lexical relationship type. Known ones:" + Arrays.toString(relNames.keySet().stream().map(n -> n + "s").toArray());
            }
            ArrayNode annotationArray =  JsonNodeFactory.instance.arrayNode();
            HashSet<String> found = new HashSet<>();
            getRelated(request.params(":word"), reltype, null, Integer.parseInt(request.params(":senses"))).stream().forEach(sw -> {
                ObjectNode syn = JsonNodeFactory.instance.objectNode();
                String pos = sw.getPOS().getLabel();
                String lemma = sw.getLemma();
                if(found.contains(pos + "_" + lemma))
                    return;
                found.add(pos + "_" + lemma);
                syn.put("POS", pos);
                syn.put("word", lemma);
                annotationArray.add(syn);
            });

            response.type("application/json; charset=utf-8");
            return annotationArray.toString();
        });

        /**
         * List the definitions of a given word
         * */
        get("/definition/:word", (request, response) -> {
            ArrayNode definitionArray =  JsonNodeFactory.instance.arrayNode();
            HashSet<String> seenGlosses = new HashSet<>();
            for(IndexWord w : dictionary.lookupAllIndexWords(request.params(":word")).getIndexWordArray()){
                for(Synset sense:w.getSenses()) {
                    if(seenGlosses.contains(sense.getGloss()))
                        continue;
                    ObjectNode syn = JsonNodeFactory.instance.objectNode();
                    seenGlosses.add(sense.getGloss());
                    syn.put("POS", w.getPOS().getLabel());
                    syn.put("gloss", sense.getGloss());
                    syn.put("other terms", Arrays.deepToString(sense.getWords().stream().map(tw->tw.getLemma()).collect(Collectors.toSet()).toArray()));
                    definitionArray.add(syn);
                }
            }

            response.type("application/json; charset=utf-8");
            return definitionArray.toString();
        });


    }

    /**
     * Return the words with a given relationship from the origin one, restricting them to the POS if specified
     * @param origin the original word (e.g. "cat")
     * @param relationship the relationship to find (e.g. "hypernym")
     * @param accepted_pos the list of POS to be considered, null to use them all
     * @param maxDepth how deep to go in relationship tree
     * */
    private static List<Word> getRelated(String origin, Relationships relationship, List<POS> accepted_pos, int maxDepth) throws JWNLException {
        ArrayList<Word> retVal = new ArrayList<>();
        for(POS p: accepted_pos == null ? POS.getAllPOS() : accepted_pos){
            IndexWord w = null;
            try {
                w = dictionary.lookupIndexWord(p,  origin);
            } catch (JWNLException e) {
                e.printStackTrace();
            }
            if(w == null)
                continue;
            for(Synset sense:w.getSenses()){
                List<PointerTargetNodeList> related = null;
                if(relationship == Relationships.HYPERNYM){
                    PointerTargetTree relatedTree = PointerUtils.getHypernymTree(sense, maxDepth);
                    related = relatedTree.toList();
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.HYPONYM){
                    PointerTargetTree relatedTree = PointerUtils.getHyponymTree(sense, maxDepth);
                    related = relatedTree.toList();
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.ANTONYM){

                    related = new LinkedList<>();
                    related.add(PointerUtils.getAntonyms(sense));
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.HOLONYM){
                    related = new LinkedList<>();
                    related.add(PointerUtils.getMemberHolonyms(sense));
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.MERONYM){
                    related = PointerUtils.getInheritedMeronyms(sense).toList();
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.SYNONYM){
                    related = PointerUtils.getSynonymTree(sense, maxDepth).toList();
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.CAUSE){
                    related = PointerUtils.getCauseTree(sense, maxDepth).toList();
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.SUBSTANCE_HOLONYM){
                    related = new LinkedList<>();
                    related.add(PointerUtils.getSubstanceHolonyms(sense));
                    if(related.size() == 0)
                        continue;
                }
                if(relationship == Relationships.SUBSTANCE_MERONYM){
                    related = new LinkedList<>();
                    related.add(PointerUtils.getSubstanceMeronyms(sense));
                    if(related.size() == 0)
                        continue;
                }
                if(related == null){
                    throw new RuntimeException("unknown lexical relationship " + relationship);
                }

                for (PointerTargetNodeList ptnl:related){

                    for(PointerTargetNode node:ptnl){
                        retVal.add(node.getSynset().getWords().get(0));
                    }
                }
            }
        }
        return retVal;
    }
    private static String sendAnnotations( List<Annotation> list, Response res){
        JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
        ObjectNode retVal = nodeFactory.objectNode();
        ArrayNode annotationArray = retVal.putArray("annotations");

        for(Annotation ann:list){
            ObjectNode annotation = nodeFactory.objectNode();
            annotation.put("span_start", ann.getStart());
            annotation.put("span_end", ann.getEnd());
            if(ann.getAnnotation() != null){
                annotation.set("annotation", ann.getAnnotation());
            }
            annotationArray.add(annotation);
        }
        res.type("application/json; charset=utf-8");
        return retVal.toString();
    }

    private static Map<String, String> getParams(String rawParameter) {
        String[] params = rawParameter.split(",");
        Map<String, String> map = new HashMap<>();
        for (String param : params) {
            String[] spl = param.split("=");
            try{
                String name = spl[0].trim();
                String value = spl[1];
                map.put(name, value);
            }
            catch(ArrayIndexOutOfBoundsException e){
                throw new RuntimeException("Invalid parameter from, must me a comma separated list of assignments, like w=cat,n=v");
            }
        }
        return map;
    }

    /**
     * Return a Set containing both the original words and their (regular) English plural.
     *
     * */
    private static Set<String> withPlurals(Set<String> original){
        Set ret = new HashSet<String> (original.size() * 2);
        for(String o: original) {
            ret.add(o);
            ret.add(English.plural(o));
        }
        return ret;
    }
}